from __future__ import annotations

"""Enhanced public-catalog discovery for the supermarket price pipeline.

This wrapper deliberately stays on public, robots-allowed pages. It augments the
existing refresh.py discovery with:
- Sitemap URLs advertised in robots.txt.
- Conventional sitemap.xml / sitemap_index.xml fallbacks.
- A small set of public catalog seed pages for retailers that do not publish a sitemap.

No authenticated/private endpoints are used.
"""

import os
import re
import sys
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

import refresh as base
from common import RobotsCache


def _host(value: str) -> str:
    host = (urlparse(value).hostname or "").lower()
    return host[4:] if host.startswith("www.") else host


def _robots_sitemaps(session: requests.Session, base_url: str) -> list[str]:
    root = f"{urlparse(base_url).scheme}://{urlparse(base_url).netloc}"
    robots_url = urljoin(root, "/robots.txt")
    try:
        response = session.get(robots_url, timeout=12)
        if not response.ok:
            return []
    except requests.RequestException:
        return []

    found: list[str] = []
    for raw_line in response.text.splitlines():
        line = raw_line.strip()
        if not line.lower().startswith("sitemap:"):
            continue
        value = line.split(":", 1)[1].strip()
        if value and _host(value) == _host(base_url):
            found.append(value)
    return list(dict.fromkeys(found))


def _working_sitemaps(session: requests.Session, config: dict) -> list[str]:
    base_url = str(config["base_url"])
    root = f"{urlparse(base_url).scheme}://{urlparse(base_url).netloc}"
    candidates = list(config.get("sitemaps") or [])
    candidates.extend(_robots_sitemaps(session, base_url))
    candidates.extend(
        [
            urljoin(root, "/sitemap.xml"),
            urljoin(root, "/sitemap_index.xml"),
            urljoin(root, "/sitemap-index.xml"),
        ]
    )

    working: list[str] = []
    for url in dict.fromkeys(candidates):
        if _host(url) != _host(base_url):
            continue
        try:
            response = session.get(url, timeout=15)
            if not response.ok:
                continue
            text = response.text.lstrip()
            if "<urlset" not in text[:1000].lower() and "<sitemapindex" not in text[:1000].lower():
                continue
            working.append(url)
        except requests.RequestException:
            continue
    return working


def _seed_product_urls(session: requests.Session, config: dict) -> list[str]:
    seeds = list(config.get("catalog_seed_urls") or [])
    if not seeds:
        return []

    pattern = re.compile(config.get("product_url_regex") or r".", re.I)
    robots = RobotsCache(session)
    max_links = max(1, int(os.environ.get("MAX_SEED_PRODUCT_LINKS_PER_RETAILER", "600")))
    product_urls: list[str] = []

    for seed in seeds:
        if len(product_urls) >= max_links:
            break
        if _host(seed) != _host(config["base_url"]) or not robots.allowed(seed):
            continue
        try:
            response = session.get(seed, timeout=20)
            response.raise_for_status()
            if "text/html" not in response.headers.get("content-type", "").lower():
                continue
        except requests.RequestException:
            continue

        soup = BeautifulSoup(response.text, "html.parser")
        for anchor in soup.find_all("a", href=True):
            href = urljoin(seed, str(anchor.get("href") or "").strip())
            parsed = urlparse(href)
            if parsed.scheme not in {"http", "https"}:
                continue
            if _host(href) != _host(config["base_url"]):
                continue
            # Strip fragments; retain meaningful query strings because some storefronts
            # use them to identify the selected store/offer.
            href = parsed._replace(fragment="").geturl()
            if pattern.search(parsed.path) and href not in product_urls:
                product_urls.append(href)
                if len(product_urls) >= max_links:
                    break

    return product_urls


_original_discover = base.discover_sitemap_urls


def enhanced_discovery(session: requests.Session, config: dict, *, max_sitemaps: int = 60) -> list[str]:
    enriched = dict(config)
    enriched["sitemaps"] = _working_sitemaps(session, config)

    pages: list[str] = []
    if enriched["sitemaps"]:
        pages.extend(_original_discover(session, enriched, max_sitemaps=max_sitemaps))
    pages.extend(_seed_product_urls(session, config))

    result = list(dict.fromkeys(pages))
    print(
        f"[{config['slug']}] public discovery: sitemaps={len(enriched['sitemaps'])} "
        f"candidate_pages={len(result)}",
        file=sys.stderr,
    )
    return result


base.discover_sitemap_urls = enhanced_discovery


if __name__ == "__main__":
    raise SystemExit(base.main())
