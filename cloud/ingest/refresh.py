from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urljoin, urlparse

import requests

from common import RobotsCache, make_session, sleep_polite
from connectors import CONNECTORS, CatalogRecord, PageConnector
from supabase_store import SupabaseStore

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "retailers.json"


def load_configs() -> list[dict[str, Any]]:
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def xml_locations(xml_text: str) -> tuple[str, list[str]]:
    root = ET.fromstring(xml_text)
    tag = root.tag.lower()
    kind = "index" if tag.endswith("sitemapindex") else "urlset"
    locations = [
        (node.text or "").strip()
        for node in root.iter()
        if node.tag.lower().endswith("loc") and (node.text or "").strip()
    ]
    return kind, locations


def discover_sitemap_urls(
    session: requests.Session,
    config: dict[str, Any],
    *,
    max_sitemaps: int = 60,
) -> list[str]:
    queue = list(dict.fromkeys(config.get("sitemaps") or []))
    seen_sitemaps: set[str] = set()
    pages: list[str] = []
    base_host = urlparse(config["base_url"]).netloc.lower()

    while queue and len(seen_sitemaps) < max_sitemaps:
        sitemap_url = queue.pop(0)
        if sitemap_url in seen_sitemaps:
            continue
        seen_sitemaps.add(sitemap_url)
        try:
            response = session.get(sitemap_url, timeout=25)
            response.raise_for_status()
            kind, locations = xml_locations(response.text)
        except (requests.RequestException, ET.ParseError, ValueError) as exc:
            print(f"[{config['slug']}] sitemap skipped {sitemap_url}: {exc}", file=sys.stderr)
            continue

        if kind == "index":
            for child in locations:
                if urlparse(child).netloc.lower() == base_host:
                    queue.append(child)
        else:
            pages.extend(
                location
                for location in locations
                if urlparse(location).netloc.lower() == base_host
            )
    return list(dict.fromkeys(pages))


def ingest_record(
    store: SupabaseStore,
    retailer_id: int,
    retailer_slug: str,
    record: CatalogRecord,
    *,
    map_source: bool = True,
) -> None:
    store.upsert_product(record.product)
    if map_source:
        store.upsert_source(
            barcode=record.product.barcode,
            retailer_id=retailer_id,
            source_url=record.product.source_url,
            branch_key=record.branch_key,
        )
    store.record_price(retailer_slug, record.branch_key, record.product)


def run_bulk_connector(
    mode: str,
    config: dict[str, Any],
    store: SupabaseStore,
    retailer_id: int,
    session: requests.Session,
) -> tuple[int, int]:
    connector = CONNECTORS.get(config["slug"])
    if connector is None:
        return 0, 0

    ok = 0
    failed = 0
    iterator = connector.discover(session) if mode == "discover" else connector.refresh(session)
    for record in iterator:
        try:
            ingest_record(store, retailer_id, config["slug"], record)
            ok += 1
        except Exception as exc:  # isolate one malformed product from the whole retailer
            failed += 1
            print(f"[{config['slug']}] connector record failed: {exc}", file=sys.stderr)
    return ok, failed


def run_discovery(
    config: dict[str, Any],
    store: SupabaseStore,
    retailer_id: int,
    session: requests.Session,
    robots: RobotsCache,
    *,
    max_new_pages: int,
) -> tuple[int, int]:
    bulk_ok, bulk_failed = run_bulk_connector("discover", config, store, retailer_id, session)

    pattern = re.compile(config.get("product_url_regex") or r".", re.I)
    known_urls = store.source_urls(retailer_id)
    candidates = [
        url
        for url in discover_sitemap_urls(session, config)
        if pattern.search(urlparse(url).path) and url not in known_urls
    ]
    if max_new_pages > 0:
        candidates = candidates[:max_new_pages]

    ok = bulk_ok
    failed = bulk_failed
    delay = float(config.get("crawl_delay_seconds") or 1.0)

    print(f"[{config['slug']}] discovery candidates={len(candidates)} known={len(known_urls)}")
    for index, url in enumerate(candidates, start=1):
        if not robots.allowed(url):
            print(f"[{config['slug']}] robots denied {url}")
            continue
        try:
            product = PageConnector.fetch(session, url=url)
            if product is None:
                # No unambiguous direct GTIN => intentionally ignore the page.
                continue
            record = CatalogRecord(product=product)
            ingest_record(store, retailer_id, config["slug"], record)
            ok += 1
        except Exception as exc:
            failed += 1
            print(f"[{config['slug']}] discovery failed {url}: {exc}", file=sys.stderr)
        finally:
            if index != len(candidates):
                sleep_polite(delay)
    return ok, failed


def run_refresh(
    config: dict[str, Any],
    store: SupabaseStore,
    retailer_id: int,
    session: requests.Session,
    robots: RobotsCache,
    *,
    max_page_sources: int,
) -> tuple[int, int]:
    bulk_ok, bulk_failed = run_bulk_connector("refresh", config, store, retailer_id, session)
    ok = bulk_ok
    failed = bulk_failed
    delay = float(config.get("crawl_delay_seconds") or 1.0)

    sources = list(store.iter_sources(retailer_id=retailer_id, max_items=max_page_sources))
    print(f"[{config['slug']}] page refresh sources={len(sources)} bulk={bulk_ok}")
    for index, source in enumerate(sources, start=1):
        source_id = int(source["id"])
        url = str(source["source_url"])
        barcode = str(source["barcode"])
        branch_key = str(source.get("branch_key") or "online")

        if not robots.allowed(url):
            store.mark_source_error(source_id, "robots_denied")
            continue

        try:
            product = PageConnector.fetch(session, url=url, expected_barcode=barcode)
            if product is None:
                store.mark_source_error(source_id, "barcode_not_verified_on_page")
                failed += 1
                continue
            store.upsert_product(product)
            if product.price is not None:
                store.record_price(config["slug"], branch_key, product)
                store.mark_source_ok(source_id)
                ok += 1
            else:
                # Do not keep an old price alive if the page no longer exposes a price.
                store.mark_source_error(source_id, "price_missing_or_unavailable")
        except Exception as exc:
            failed += 1
            store.mark_source_error(source_id, f"{type(exc).__name__}: {exc}")
            print(f"[{config['slug']}] refresh failed {url}: {exc}", file=sys.stderr)
        finally:
            if index != len(sources):
                sleep_polite(delay)
    return ok, failed


def main() -> int:
    parser = argparse.ArgumentParser(description="Saudi supermarket catalog discovery and price refresh")
    parser.add_argument("--mode", choices=["refresh", "discover"], default="refresh")
    parser.add_argument("--retailer", action="append", help="Limit to one or more retailer slugs")
    parser.add_argument(
        "--max-pages",
        type=int,
        default=int(os.environ.get("MAX_PAGE_SOURCES_PER_RETAILER", "800")),
        help="Fallback page-source cap per retailer. Bulk connectors are not capped.",
    )
    args = parser.parse_args()

    configs = load_configs()
    selected = set(args.retailer or [])
    if selected:
        configs = [config for config in configs if config["slug"] in selected]
        unknown = selected - {config["slug"] for config in configs}
        if unknown:
            raise SystemExit(f"Unknown retailer(s): {', '.join(sorted(unknown))}")

    store = SupabaseStore()
    all_configs = load_configs()
    store.seed_retailers(all_configs)
    ids = store.retailer_ids()
    session = make_session()
    robots = RobotsCache(session)

    totals: dict[str, int] = defaultdict(int)
    for config in configs:
        retailer_id = ids.get(config["slug"])
        if retailer_id is None:
            print(f"[{config['slug']}] missing retailer id", file=sys.stderr)
            totals["failed"] += 1
            continue
        try:
            if args.mode == "discover":
                ok, failed = run_discovery(
                    config,
                    store,
                    retailer_id,
                    session,
                    robots,
                    max_new_pages=args.max_pages,
                )
            else:
                ok, failed = run_refresh(
                    config,
                    store,
                    retailer_id,
                    session,
                    robots,
                    max_page_sources=args.max_pages,
                )
            totals["ok"] += ok
            totals["failed"] += failed
        except Exception as exc:
            totals["failed"] += 1
            print(f"[{config['slug']}] retailer isolated after error: {exc}", file=sys.stderr)

    changed = store.rebuild_snapshots()
    print(
        json.dumps(
            {
                "mode": args.mode,
                "retailers": len(configs),
                "records_ok": totals["ok"],
                "records_failed": totals["failed"],
                "changed_snapshots": changed,
            },
            ensure_ascii=False,
        )
    )
    # Partial retailer failures should not block fresh data from healthy retailers.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
