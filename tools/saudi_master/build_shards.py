from __future__ import annotations

import argparse
import gzip
import html
import io
import json
import os
import random
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

SFDA_BASE = "https://www.sfda.gov.sa"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/151 Safari/537.36 MoqarinAlasaarSaudiMaster/1.0"
TIMEOUT = 20


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def valid_gtin(value: Any) -> str | None:
    digits = re.sub(r"\D+", "", str(value or ""))
    if len(digits) not in (8, 12, 13, 14):
        return None
    total = 0
    for i, ch in enumerate(reversed(digits[:-1])):
        total += int(ch) * (3 if i % 2 == 0 else 1)
    return digits if int(digits[-1]) == (10 - total % 10) % 10 else None


def split_lang(value: str | None) -> tuple[str | None, str | None]:
    value = (value or "").strip() or None
    if not value:
        return None, None
    return (value, None) if re.search(r"[\u0600-\u06ff]", value) else (None, value)


def shard_name(barcode: str) -> str:
    return f"gtin-{barcode[:3].rjust(3, '0')}"


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9,ar-SA;q=0.8",
        "Connection": "keep-alive",
    })
    return s


def get_text(session: requests.Session, url: str, retries: int = 4) -> str:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = session.get(url, timeout=TIMEOUT, allow_redirects=True)
            if r.status_code == 200 and "Request Rejected" not in r.text:
                return r.text
            if r.status_code in (403, 429, 500, 502, 503, 504) or "Request Rejected" in r.text:
                time.sleep(min(8.0, 1.2 * (2 ** attempt)) + random.random())
                continue
            r.raise_for_status()
            return r.text
        except Exception as exc:
            last = exc
            time.sleep(min(8.0, 1.2 * (2 ** attempt)) + random.random())
    raise RuntimeError(f"failed GET {url}: {last!r}")


def page_count(soup: BeautifulSoup) -> int | None:
    values: list[int] = []
    for a in soup.select("a[title='Go to last page']"):
        href = a.get("href") or ""
        m = re.search(r"[?&]page=(\d+)", href)
        if m:
            values.append(int(m.group(1)) + 1)
    return max(values) if values else None


def row_cells(tr) -> list[str]:
    return [td.get_text(" ", strip=True) for td in tr.find_all("td")]


def parse_food_page(session: requests.Session, page: int) -> tuple[list[dict[str, Any]], int | None]:
    url = f"{SFDA_BASE}/en/products?page={page}"
    soup = BeautifulSoup(get_text(session, url), "html.parser")
    out: list[dict[str, Any]] = []
    for tr in soup.select("tbody tr"):
        cells = row_cells(tr)
        if len(cells) < 4:
            continue
        barcode = valid_gtin(cells[0])
        if not barcode:
            continue
        ref, brand, trade = cells[1].strip(), cells[2].strip() or None, cells[3].strip() or None
        name_ar, name_en = split_lang(trade)
        a = tr.find("a", href=re.compile("details_data"))
        source_url = urljoin(SFDA_BASE, a.get("href")) if a and a.get("href") else url
        out.append({
            "barcode": barcode,
            "name_ar": name_ar,
            "name_en": name_en,
            "brand": brand,
            "quantity": None,
            "category": None,
            "manufacturing_country": None,
            "source": "sfda_food",
            "source_record_id": ref or barcode,
            "source_url": source_url,
            "confidence": 1.0,
            "saudi_signal": "sfda_food_registered_for_saudi_market",
            "updated_at": now_iso(),
        })
    return out, page_count(soup)


def parse_detail(session: requests.Session, url: str) -> dict[str, str]:
    soup = BeautifulSoup(get_text(session, url), "html.parser")
    data: dict[str, str] = {}
    for tr in soup.select("table tr"):
        th, td = tr.find("th"), tr.find("td")
        if th and td:
            data[th.get_text(" ", strip=True).lower()] = td.get_text(" ", strip=True)
    return data


def parse_cosmetic_candidate(row: dict[str, Any]) -> dict[str, Any] | None:
    session = make_session()
    detail = parse_detail(session, row["detail_url"])
    barcode = valid_gtin(detail.get("barcode"))
    if not barcode:
        return None
    raw_name = detail.get("product name") or row.get("product_name")
    name_ar, name_en = split_lang(raw_name)
    volume = (detail.get("package volume") or "").strip()
    unit = (detail.get("unit") or "").strip()
    quantity = " ".join(x for x in (volume, unit) if x) or None
    return {
        "barcode": barcode,
        "name_ar": name_ar,
        "name_en": name_en,
        "brand": (detail.get("brand name") or "").strip() or None,
        "quantity": quantity,
        "category": (detail.get("category") or row.get("category") or "").strip() or None,
        "manufacturing_country": (detail.get("country") or row.get("country") or "").strip() or None,
        "source": "sfda_cosmetics",
        "source_record_id": (detail.get("product number") or barcode).strip(),
        "source_url": row["detail_url"],
        "confidence": 1.0,
        "saudi_signal": "sfda_cosmetic_marketing_notification_saudi",
        "updated_at": now_iso(),
    }


def parse_cosmetics_page(session: requests.Session, page: int, workers: int) -> tuple[list[dict[str, Any]], int | None]:
    url = f"{SFDA_BASE}/en/cosmetics-list?page={page}"
    soup = BeautifulSoup(get_text(session, url), "html.parser")
    candidates: list[dict[str, Any]] = []
    for tr in soup.select("tbody tr"):
        cells = row_cells(tr)
        if len(cells) < 4:
            continue
        a = tr.find("a", href=re.compile("details_data"))
        if not a or not a.get("href"):
            continue
        candidates.append({
            "product_name": cells[0],
            "category": cells[1] if len(cells) > 1 else None,
            "country": cells[2] if len(cells) > 2 else None,
            "detail_url": urljoin(SFDA_BASE, a.get("href")),
        })
    out: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, 8))) as pool:
        futures = [pool.submit(parse_cosmetic_candidate, row) for row in candidates]
        for future in as_completed(futures):
            try:
                record = future.result()
            except Exception:
                record = None
            if record:
                out.append(record)
    return out, page_count(soup)


def read_manifest(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"schema_version": 1, "cursors": {}, "records_seen": {}, "updated_at": None}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {"schema_version": 1, "cursors": {}, "records_seen": {}, "updated_at": None}


def crawl(args: argparse.Namespace) -> int:
    out_dir = Path(args.out)
    delta_dir = out_dir / "delta"
    delta_dir.mkdir(parents=True, exist_ok=True)
    manifest = read_manifest(Path(args.manifest)) if args.manifest else read_manifest(Path("/nonexistent"))
    default_start = int(manifest.get("cursors", {}).get(args.source, 0) or 0)
    start = args.start_page if args.start_page is not None else default_start
    session = make_session()
    accepted = 0
    seen_pages = 0
    rejected_pages = 0
    total_pages: int | None = None
    shard_handles: dict[str, io.TextIOWrapper] = {}
    shard_paths: dict[str, Path] = {}
    try:
        page = start
        for _ in range(max(1, args.pages)):
            if total_pages is not None and page >= total_pages:
                page = 0
            try:
                if args.source == "food":
                    rows, page_total = parse_food_page(session, page)
                else:
                    rows, page_total = parse_cosmetics_page(session, page, args.workers)
            except Exception as exc:
                rejected_pages += 1
                print(json.dumps({"page": page, "source": args.source, "error": repr(exc)}, ensure_ascii=False))
                if rejected_pages >= 3:
                    break
                page += 1
                continue
            if page_total:
                total_pages = page_total
            seen_pages += 1
            for record in rows:
                name = shard_name(record["barcode"])
                if name not in shard_handles:
                    path = delta_dir / f"{name}.jsonl"
                    shard_paths[name] = path
                    shard_handles[name] = path.open("a", encoding="utf-8")
                shard_handles[name].write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                accepted += 1
            print(json.dumps({"page": page, "source": args.source, "accepted": len(rows), "total_pages": total_pages}, ensure_ascii=False))
            page += 1
            if args.delay:
                time.sleep(max(0.0, args.delay))
    finally:
        for handle in shard_handles.values():
            handle.close()
    next_page = page
    if total_pages and next_page >= total_pages:
        next_page = 0
    run = {
        "schema_version": 1,
        "source": args.source,
        "start_page": start,
        "next_page": next_page,
        "pages_requested": args.pages,
        "pages_completed": seen_pages,
        "pages_failed": rejected_pages,
        "accepted": accepted,
        "total_pages": total_pages,
        "shards": sorted(shard_paths),
        "completed_at": now_iso(),
    }
    (out_dir / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(run, ensure_ascii=False))
    return 0 if seen_pages else 2


def load_jsonl_gz(path: Path) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    if not path.exists():
        return rows
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            try:
                row = json.loads(line)
            except Exception:
                continue
            barcode = valid_gtin(row.get("barcode"))
            if barcode:
                rows[barcode] = row
    return rows


def merge_row(old: dict[str, Any] | None, new: dict[str, Any]) -> dict[str, Any]:
    if old is None:
        result = dict(new)
        result["sources"] = sorted({new.get("source")} - {None})
        return result
    result = dict(old)
    for key, value in new.items():
        if value not in (None, "", [], {}):
            result[key] = value
    sources = set(old.get("sources") or [])
    if old.get("source"):
        sources.add(old["source"])
    if new.get("source"):
        sources.add(new["source"])
    result["sources"] = sorted(sources)
    return result


def merge(args: argparse.Namespace) -> int:
    existing = load_jsonl_gz(Path(args.existing)) if args.existing else {}
    delta_path = Path(args.delta)
    if delta_path.exists():
        with delta_path.open("r", encoding="utf-8") as fh:
            for line in fh:
                try:
                    row = json.loads(line)
                except Exception:
                    continue
                barcode = valid_gtin(row.get("barcode"))
                if not barcode:
                    continue
                existing[barcode] = merge_row(existing.get(barcode), row)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            with io.TextIOWrapper(gz, encoding="utf-8") as text_out:
                for barcode in sorted(existing):
                    text_out.write(json.dumps(existing[barcode], ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"output": str(output), "records": len(existing), "bytes": output.stat().st_size}))
    return 0


def update_manifest(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    manifest = read_manifest(manifest_path)
    run = json.loads(Path(args.run).read_text(encoding="utf-8"))
    source = run["source"]
    manifest.setdefault("schema_version", 1)
    manifest.setdefault("cursors", {})[source] = int(run["next_page"])
    manifest.setdefault("records_seen", {})[source] = int(manifest.get("records_seen", {}).get(source, 0) or 0) + int(run.get("accepted", 0) or 0)
    manifest.setdefault("total_pages", {})[source] = run.get("total_pages")
    manifest.setdefault("last_runs", {})[source] = run
    manifest["updated_at"] = now_iso()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False))
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Build free incremental Saudi GTIN master shards from official SFDA pages")
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("crawl")
    c.add_argument("--source", choices=("food", "cosmetics"), required=True)
    c.add_argument("--pages", type=int, default=20)
    c.add_argument("--start-page", type=int)
    c.add_argument("--workers", type=int, default=4)
    c.add_argument("--delay", type=float, default=0.25)
    c.add_argument("--manifest")
    c.add_argument("--out", required=True)
    c.set_defaults(func=crawl)
    m = sub.add_parser("merge")
    m.add_argument("--existing")
    m.add_argument("--delta", required=True)
    m.add_argument("--output", required=True)
    m.set_defaults(func=merge)
    u = sub.add_parser("update-manifest")
    u.add_argument("--manifest", required=True)
    u.add_argument("--run", required=True)
    u.add_argument("--output", required=True)
    u.set_defaults(func=update_manifest)
    return p


def main() -> int:
    args = parser().parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
