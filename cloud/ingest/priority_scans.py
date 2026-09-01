from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable

from common import ExtractedProduct, valid_gtin
from supabase_store import SupabaseStore


def walk_rows(value: Any) -> Iterable[dict[str, Any]]:
    """Accept Wrangler's JSON output across minor CLI shape changes."""
    if isinstance(value, dict):
        barcode = value.get("barcode")
        if barcode is not None:
            yield value
        for child in value.values():
            yield from walk_rows(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_rows(child)


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def clean_text(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value or None


def main() -> int:
    parser = argparse.ArgumentParser(description="Persist D1 high-priority unknown barcode metadata")
    parser.add_argument("--input", required=True, help="Wrangler d1 execute --json output")
    parser.add_argument("--mark-sql", required=True, help="Output SQL that marks attempted rows in D1")
    args = parser.parse_args()

    raw = json.loads(Path(args.input).read_text(encoding="utf-8"))
    rows_by_barcode: dict[str, dict[str, Any]] = {}
    for row in walk_rows(raw):
        barcode = valid_gtin(str(row.get("barcode") or ""))
        if barcode:
            rows_by_barcode[barcode] = row

    store = SupabaseStore()
    imported = 0
    attempted: list[str] = []

    for barcode, row in rows_by_barcode.items():
        attempted.append(barcode)
        name_ar = clean_text(row.get("name_ar"))
        name_en = clean_text(row.get("name_en"))
        image_url = clean_text(row.get("image_url"))

        # Do not create an empty catalog row just because a camera saw a valid GTIN.
        # Metadata-only rows are safe to persist; later retailer evidence can promote/merge them.
        if not any((name_ar, name_en, image_url)):
            continue

        product = ExtractedProduct(
            barcode=barcode,
            name_ar=name_ar,
            name_en=name_en,
            brand=None,
            image_url=image_url,
            price=None,
            currency="SAR",
            source_url=f"d1://priority-scan/{barcode}",
        )
        store.upsert_product(product)
        imported += 1

    mark_path = Path(args.mark_sql)
    mark_path.parent.mkdir(parents=True, exist_ok=True)
    if attempted:
        quoted = ",".join(sql_quote(barcode) for barcode in attempted)
        mark_path.write_text(
            "BEGIN TRANSACTION;\n"
            "UPDATE missing_barcodes SET last_attempt_at=datetime('now') "
            f"WHERE barcode IN ({quoted});\n"
            "COMMIT;\n",
            encoding="utf-8",
        )
    else:
        mark_path.write_text("-- no priority scans selected\n", encoding="utf-8")

    print(
        json.dumps(
            {
                "priority_rows": len(rows_by_barcode),
                "metadata_imported": imported,
                "attempted": len(attempted),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
