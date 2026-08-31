from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.robotparser
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

USER_AGENT = "SaudiSupermarketPriceBot/1.0 (+catalog price refresh; contact repository owner)"
ARABIC_RE = re.compile(r"[\u0600-\u06FF]")
DIGIT_RE = re.compile(r"\D+")
GTIN_KEYS = {
    "gtin", "gtin8", "gtin12", "gtin13", "gtin14", "barcode", "bar_code",
    "ean", "ean8", "ean13", "upc", "upca", "upc_a",
}
PRICE_KEYS = {"price", "lowprice", "highprice", "saleprice", "finalprice", "currentprice"}


@dataclass(frozen=True)
class ExtractedProduct:
    barcode: str
    name_ar: str | None
    name_en: str | None
    brand: str | None
    image_url: str | None
    price: Decimal | None
    currency: str
    source_url: str


class RobotsCache:
    def __init__(self, session: requests.Session):
        self.session = session
        self._cache: dict[str, urllib.robotparser.RobotFileParser | None] = {}

    def allowed(self, url: str) -> bool:
        parsed = urlparse(url)
        root = f"{parsed.scheme}://{parsed.netloc}"
        if root not in self._cache:
            robots_url = urljoin(root, "/robots.txt")
            parser = urllib.robotparser.RobotFileParser()
            parser.set_url(robots_url)
            try:
                response = self.session.get(robots_url, timeout=12)
                if response.ok:
                    parser.parse(response.text.splitlines())
                    self._cache[root] = parser
                else:
                    self._cache[root] = None
            except requests.RequestException:
                self._cache[root] = None

        parser = self._cache[root]
        # If robots.txt cannot be fetched, default to a conservative no-crawl for discovery.
        return bool(parser and parser.can_fetch(USER_AGENT, url))


def make_session() -> requests.Session:
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language": "ar-SA,ar;q=0.9,en;q=0.8",
            "Cache-Control": "no-cache",
        }
    )
    return session


def valid_gtin(value: str | int | None) -> str | None:
    if value is None:
        return None
    digits = DIGIT_RE.sub("", str(value))
    if len(digits) not in {8, 12, 13, 14}:
        return None
    body = digits[:-1]
    check = int(digits[-1])
    total = 0
    for i, char in enumerate(reversed(body)):
        digit = int(char)
        total += digit * (3 if i % 2 == 0 else 1)
    expected = (10 - total % 10) % 10
    return digits if check == expected else None


def normalize_price(value: Any) -> Decimal | None:
    if value is None:
        return None
    if isinstance(value, (int, float, Decimal)):
        try:
            dec = Decimal(str(value))
        except InvalidOperation:
            return None
        return dec.quantize(Decimal("0.01")) if dec >= 0 else None

    text = str(value).strip()
    text = text.replace("٬", "").replace(",", "").replace("٫", ".")
    match = re.search(r"(?<!\d)(\d{1,7}(?:\.\d{1,2})?)(?!\d)", text)
    if not match:
        return None
    try:
        dec = Decimal(match.group(1))
    except InvalidOperation:
        return None
    if dec < 0 or dec > Decimal("100000"):
        return None
    return dec.quantize(Decimal("0.01"))


def fetch_html(session: requests.Session, url: str, *, timeout: int = 20) -> str:
    response = session.get(url, timeout=timeout, allow_redirects=True)
    response.raise_for_status()
    content_type = response.headers.get("content-type", "").lower()
    if "text/html" not in content_type and "application/xhtml" not in content_type:
        raise ValueError(f"Unexpected content type: {content_type}")
    return response.text


def _walk_json(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key).lower(), child
            yield from _walk_json(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_json(child)


def _json_ld_products(soup: BeautifulSoup) -> list[dict[str, Any]]:
    products: list[dict[str, Any]] = []
    for node in soup.find_all("script", attrs={"type": re.compile(r"ld\+json", re.I)}):
        raw = node.string or node.get_text(" ", strip=True)
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            continue

        stack = [data]
        while stack:
            current = stack.pop()
            if isinstance(current, list):
                stack.extend(current)
                continue
            if not isinstance(current, dict):
                continue
            graph = current.get("@graph")
            if isinstance(graph, list):
                stack.extend(graph)
            kind = current.get("@type")
            kinds = {str(x).lower() for x in kind} if isinstance(kind, list) else {str(kind).lower()}
            if "product" in kinds:
                products.append(current)
    return products


def _candidate_barcodes(product: dict[str, Any], html_text: str) -> set[str]:
    candidates: set[str] = set()
    for key, value in _walk_json(product):
        if key in GTIN_KEYS and isinstance(value, (str, int)):
            gtin = valid_gtin(value)
            if gtin:
                candidates.add(gtin)

    # Embedded app state often carries a direct key named barcode/ean/upc/gtin.
    patterns = [
        r'"(?:gtin(?:8|12|13|14)?|barcode|bar_code|ean(?:8|13)?|upc|upca|upc_a)"\s*:\s*"?(\d{8,14})"?',
        r"'(?:gtin(?:8|12|13|14)?|barcode|bar_code|ean(?:8|13)?|upc|upca|upc_a)'\s*:\s*'?(\d{8,14})'?",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, html_text, flags=re.I):
            gtin = valid_gtin(match.group(1))
            if gtin:
                candidates.add(gtin)
    return candidates


def _best_offer(product: dict[str, Any]) -> tuple[Decimal | None, str]:
    offers = product.get("offers")
    candidates: list[dict[str, Any]] = []
    if isinstance(offers, dict):
        candidates.append(offers)
    elif isinstance(offers, list):
        candidates.extend(x for x in offers if isinstance(x, dict))

    for offer in candidates:
        price = normalize_price(offer.get("price") or offer.get("lowPrice"))
        if price is not None:
            currency = str(offer.get("priceCurrency") or "SAR").upper()
            return price, currency
    return None, "SAR"


def _clean_text(value: Any) -> str | None:
    if value is None:
        return None
    text = re.sub(r"\s+", " ", str(value)).strip()
    return text or None


def _brand(product: dict[str, Any]) -> str | None:
    brand = product.get("brand")
    if isinstance(brand, dict):
        return _clean_text(brand.get("name"))
    if isinstance(brand, list):
        for item in brand:
            if isinstance(item, dict) and item.get("name"):
                return _clean_text(item.get("name"))
            if isinstance(item, str):
                return _clean_text(item)
    return _clean_text(brand)


def _image(product: dict[str, Any], soup: BeautifulSoup, base_url: str) -> str | None:
    image = product.get("image")
    if isinstance(image, list):
        image = next((x for x in image if isinstance(x, str)), None)
    elif isinstance(image, dict):
        image = image.get("url") or image.get("contentUrl")
    if isinstance(image, str) and image.strip():
        return urljoin(base_url, image.strip())

    meta = soup.find("meta", attrs={"property": "og:image"})
    if meta and meta.get("content"):
        return urljoin(base_url, str(meta["content"]).strip())
    return None


def extract_product(html: str, source_url: str, expected_barcode: str | None = None) -> ExtractedProduct | None:
    soup = BeautifulSoup(html, "html.parser")
    products = _json_ld_products(soup)
    page_candidates: set[str] = set()
    for product in products:
        page_candidates |= _candidate_barcodes(product, html)

    # If JSON-LD is absent, still inspect embedded application state for explicit GTIN keys.
    if not products:
        page_candidates |= _candidate_barcodes({}, html)

    if expected_barcode:
        expected = valid_gtin(expected_barcode)
        if not expected or expected not in page_candidates:
            return None
        barcode = expected
    else:
        # Discovery requires an unambiguous direct GTIN. Do not guess by SKU/title/size.
        if len(page_candidates) != 1:
            return None
        barcode = next(iter(page_candidates))

    selected = products[0] if products else {}
    for product in products:
        if barcode in _candidate_barcodes(product, html):
            selected = product
            break

    name = _clean_text(selected.get("name"))
    name_ar = name if name and ARABIC_RE.search(name) else None
    name_en = name if name and not ARABIC_RE.search(name) else None
    price, currency = _best_offer(selected)

    if price is None:
        # Standards-based metadata fallbacks only; avoid generic numeric scraping.
        for selector in [
            ('meta[property="product:price:amount"]', "content"),
            ('meta[itemprop="price"]', "content"),
        ]:
            node = soup.select_one(selector[0])
            if node and node.get(selector[1]):
                price = normalize_price(node.get(selector[1]))
                if price is not None:
                    break
    currency_node = soup.select_one('meta[property="product:price:currency"], meta[itemprop="priceCurrency"]')
    if currency_node and currency_node.get("content"):
        currency = str(currency_node["content"]).upper()

    return ExtractedProduct(
        barcode=barcode,
        name_ar=name_ar,
        name_en=name_en,
        brand=_brand(selected),
        image_url=_image(selected, soup, source_url),
        price=price,
        currency=currency or "SAR",
        source_url=source_url,
    )


def stable_hash(value: Any) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def sleep_polite(seconds: float) -> None:
    time.sleep(max(0.25, seconds))
