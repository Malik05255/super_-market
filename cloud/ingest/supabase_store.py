from __future__ import annotations

import os
from typing import Any, Iterator
from urllib.parse import quote

import requests

from common import ExtractedProduct


class SupabaseStore:
    def __init__(self) -> None:
        self.base_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
        self.service_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
        if not self.base_url or not self.service_key:
            raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required")
        self.session = requests.Session()
        self.session.headers.update(
            {
                "apikey": self.service_key,
                "Authorization": f"Bearer {self.service_key}",
                "Content-Type": "application/json",
                "Prefer": "return=minimal",
            }
        )

    def _url(self, path: str) -> str:
        return f"{self.base_url}/rest/v1/{path.lstrip('/')}"

    def seed_retailers(self, configs: list[dict[str, Any]]) -> None:
        rows = [
            {
                "slug": item["slug"],
                "name_ar": item["name_ar"],
                "name_en": item["name_en"],
                "base_url": item.get("base_url"),
                "active": True,
            }
            for item in configs
        ]
        response = self.session.post(
            self._url("retailers?on_conflict=slug"),
            json=rows,
            headers={**self.session.headers, "Prefer": "resolution=merge-duplicates,return=minimal"},
            timeout=30,
        )
        response.raise_for_status()

    def retailer_ids(self) -> dict[str, int]:
        response = self.session.get(self._url("retailers?select=id,slug&active=eq.true"), timeout=30)
        response.raise_for_status()
        return {str(row["slug"]): int(row["id"]) for row in response.json()}

    def upsert_product(self, product: ExtractedProduct) -> None:
        payload = {
            "p_barcode": product.barcode,
            "p_name_ar": product.name_ar,
            "p_name_en": product.name_en,
            "p_brand": product.brand,
            "p_image_url": product.image_url,
            "p_gs1_verified": False,
        }
        response = self.session.post(self._url("rpc/upsert_product_metadata"), json=payload, timeout=30)
        response.raise_for_status()

    def record_price(self, retailer_slug: str, branch_key: str, product: ExtractedProduct) -> None:
        if product.price is None:
            return
        payload = {
            "p_barcode": product.barcode,
            "p_retailer_slug": retailer_slug,
            "p_branch_key": branch_key or "online",
            "p_price": float(product.price),
            "p_currency": product.currency or "SAR",
            "p_source_url": product.source_url,
        }
        response = self.session.post(self._url("rpc/record_retailer_price"), json=payload, timeout=30)
        response.raise_for_status()

    def upsert_source(
        self,
        *,
        barcode: str,
        retailer_id: int,
        source_url: str,
        branch_key: str = "online",
    ) -> None:
        payload = {
            "barcode": barcode,
            "retailer_id": retailer_id,
            "source_url": source_url,
            "branch_key": branch_key or "online",
            "active": True,
            "last_error": None,
        }
        response = self.session.post(
            self._url("retailer_product_sources?on_conflict=retailer_id,source_url"),
            json=payload,
            headers={**self.session.headers, "Prefer": "resolution=merge-duplicates,return=minimal"},
            timeout=30,
        )
        response.raise_for_status()

    def mark_source_ok(self, source_id: int) -> None:
        response = self.session.patch(
            self._url(f"retailer_product_sources?id=eq.{source_id}"),
            json={"last_checked_at": self._iso_now(), "last_error": None},
            timeout=30,
        )
        response.raise_for_status()

    def mark_source_error(self, source_id: int, error: str) -> None:
        response = self.session.patch(
            self._url(f"retailer_product_sources?id=eq.{source_id}"),
            json={"last_checked_at": self._iso_now(), "last_error": error[:1000]},
            timeout=30,
        )
        response.raise_for_status()

    def source_urls(self, retailer_id: int) -> set[str]:
        urls: set[str] = set()
        for row in self.iter_sources(retailer_id=retailer_id):
            source = row.get("source_url")
            if source:
                urls.add(str(source))
        return urls

    def iter_sources(
        self,
        retailer_id: int | None = None,
        page_size: int = 500,
        max_items: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        offset = 0
        yielded = 0
        while True:
            limit = page_size
            if max_items is not None:
                remaining = max_items - yielded
                if remaining <= 0:
                    break
                limit = min(limit, remaining)

            query = (
                "retailer_product_sources?"
                "select=id,barcode,retailer_id,source_url,branch_key,active,last_checked_at"
                "&active=eq.true"
                f"&limit={limit}&offset={offset}"
                "&order=last_checked_at.asc.nullsfirst,id.asc"
            )
            if retailer_id is not None:
                query += f"&retailer_id=eq.{retailer_id}"
            response = self.session.get(self._url(query), timeout=30)
            response.raise_for_status()
            rows = response.json()
            for row in rows:
                yield row
                yielded += 1
                if max_items is not None and yielded >= max_items:
                    return
            if len(rows) < limit:
                break
            offset += len(rows)

    def rebuild_snapshots(self) -> int:
        response = self.session.post(
            self._url("rpc/rebuild_product_snapshots"),
            json={},
            headers={**self.session.headers, "Prefer": "return=representation"},
            timeout=120,
        )
        response.raise_for_status()
        data = response.json()
        if isinstance(data, int):
            return data
        if isinstance(data, list) and data:
            first = data[0]
            if isinstance(first, int):
                return first
            if isinstance(first, dict):
                return int(next(iter(first.values())))
        if isinstance(data, dict) and data:
            return int(next(iter(data.values())))
        return 0

    def changed_snapshots(self, since_iso: str, page_size: int = 1000) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        offset = 0
        encoded_since = quote(since_iso, safe=":-T.Z+")
        while True:
            response = self.session.get(
                self._url(
                    "product_snapshots?select=barcode,payload,payload_hash,updated_at"
                    f"&updated_at=gte.{encoded_since}&order=updated_at.asc"
                    f"&limit={page_size}&offset={offset}"
                ),
                timeout=60,
            )
            response.raise_for_status()
            page = response.json()
            rows.extend(page)
            if len(page) < page_size:
                break
            offset += len(page)
        return rows

    def system_state(self, key: str) -> dict[str, Any] | None:
        encoded = quote(key, safe="")
        response = self.session.get(
            self._url(f"system_state?select=key,value,updated_at&key=eq.{encoded}&limit=1"),
            timeout=30,
        )
        response.raise_for_status()
        rows = response.json()
        return rows[0] if rows else None

    @staticmethod
    def _iso_now() -> str:
        from datetime import datetime, timezone

        return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
