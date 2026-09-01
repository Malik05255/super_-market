from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Iterable

import requests

from common import ExtractedProduct, extract_product, fetch_html


@dataclass(frozen=True)
class CatalogRecord:
    product: ExtractedProduct
    branch_key: str = "online"


class RetailerConnector(ABC):
    """Optional high-throughput connector for a retailer's permitted public catalog/feed."""

    slug: str

    @abstractmethod
    def discover(self, session: requests.Session) -> Iterable[CatalogRecord]:
        raise NotImplementedError

    @abstractmethod
    def refresh(self, session: requests.Session) -> Iterable[CatalogRecord]:
        raise NotImplementedError


class PageConnector:
    """Conservative fallback for a verified retailer product page."""

    @staticmethod
    def fetch(
        session: requests.Session,
        *,
        url: str,
        expected_barcode: str | None = None,
    ) -> ExtractedProduct | None:
        html = fetch_html(session, url)
        return extract_product(html, url, expected_barcode=expected_barcode)


# Registry for retailer-specific bulk connectors. Keep empty rather than reverse-engineering
# private/authenticated endpoints. Add a connector only when a public/authorized feed is known.
CONNECTORS: dict[str, RetailerConnector] = {}
