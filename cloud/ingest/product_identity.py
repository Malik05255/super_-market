from __future__ import annotations

import hashlib
import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from common import ExtractedProduct

SPACE_RE = re.compile(r"\s+")
PUNCT_RE = re.compile(r"[^0-9a-z\u0600-\u06ff]+", re.IGNORECASE)

# Unit patterns intentionally require an explicit unit; a bare number is never treated as size.
SIZE_PATTERNS = [
    (re.compile(r"(?<!\d)(\d+(?:[\.,]\d+)?)\s*(?:ml|مل|مليلتر|millilit(?:er|re)s?)(?![a-z])", re.I), "ml", Decimal("1")),
    (re.compile(r"(?<!\d)(\d+(?:[\.,]\d+)?)\s*(?:l|ltr|liter|litre|لتر)(?![a-z])", re.I), "ml", Decimal("1000")),
    (re.compile(r"(?<!\d)(\d+(?:[\.,]\d+)?)\s*(?:g|gm|gram|جرام|غرام)(?![a-z])", re.I), "g", Decimal("1")),
    (re.compile(r"(?<!\d)(\d+(?:[\.,]\d+)?)\s*(?:kg|kilogram|كيلو|كجم)(?![a-z])", re.I), "g", Decimal("1000")),
]

PACK_PATTERNS = [
    re.compile(r"(?<!\d)(\d{1,3})\s*[x×]\s*", re.I),
    re.compile(r"(?:pack|pk|عبوة|عبوات|حبة|حبات)\s*(?:of\s*)?(\d{1,3})(?!\d)", re.I),
    re.compile(r"(?<!\d)(\d{1,3})\s*(?:pack|pk|pcs|pieces|حبة|حبات|عبوة|عبوات)(?!\w)", re.I),
]

# Words that describe packaging/marketing rather than product variant.
STOP_TOKENS = {
    "can", "cans", "bottle", "bottles", "pet", "pack", "packs", "piece", "pieces",
    "ml", "ltr", "liter", "litre", "g", "gm", "gram", "kg",
    "علبة", "علب", "عبوة", "عبوات", "زجاجة", "زجاجات", "حبة", "حبات",
    "مل", "لتر", "جرام", "غرام", "كيلو", "كجم",
}

# High-signal variant terms. This list prevents merging e.g. regular with diet/zero.
VARIANT_GROUPS = {
    "zero": {"zero", "زيرو", "صفر"},
    "diet": {"diet", "دايت", "حمية"},
    "light": {"light", "لايت", "خفيف"},
    "original": {"original", "regular", "classic", "اصلي", "أصلي", "عادي", "كلاسيك"},
    "cherry": {"cherry", "كرز"},
    "vanilla": {"vanilla", "فانيلا"},
    "lemon": {"lemon", "ليمون"},
    "lime": {"lime", "لايم"},
}


@dataclass(frozen=True)
class ProductIdentity:
    identity_key: str | None
    variant: str | None
    net_content_value: Decimal | None
    net_content_unit: str | None
    pack_count: int
    confidence: float
    method: str


def _normalize(text: str | None) -> str:
    if not text:
        return ""
    value = unicodedata.normalize("NFKC", text).lower().strip()
    value = value.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    value = value.replace("ة", "ه").replace("ى", "ي")
    value = PUNCT_RE.sub(" ", value)
    return SPACE_RE.sub(" ", value).strip()


def _parse_decimal(raw: str) -> Decimal | None:
    try:
        return Decimal(raw.replace(",", "."))
    except InvalidOperation:
        return None


def _extract_size(text: str) -> tuple[Decimal | None, str | None]:
    found: list[tuple[int, Decimal, str]] = []
    for pattern, unit, multiplier in SIZE_PATTERNS:
        for match in pattern.finditer(text):
            number = _parse_decimal(match.group(1))
            if number is None or number <= 0:
                continue
            value = (number * multiplier).quantize(Decimal("0.001"))
            if value > Decimal("100000"):
                continue
            found.append((match.start(), value, unit))
    if not found:
        return None, None
    found.sort(key=lambda item: item[0])
    _, value, unit = found[0]
    return value, unit


def _extract_pack_count(text: str) -> int:
    for pattern in PACK_PATTERNS:
        match = pattern.search(text)
        if match:
            try:
                count = int(match.group(1))
            except (TypeError, ValueError):
                continue
            if 1 <= count <= 100:
                return count
    return 1


def _variant(text: str) -> str | None:
    tokens = set(_normalize(text).split())
    matched = []
    for canonical, synonyms in VARIANT_GROUPS.items():
        if tokens.intersection({_normalize(value) for value in synonyms}):
            matched.append(canonical)
    if not matched:
        return None
    return "+".join(sorted(set(matched)))


def _brand(product: ExtractedProduct) -> str:
    return _normalize(product.brand)


def _name_tokens(product: ExtractedProduct, brand: str) -> list[str]:
    name = _normalize(product.name_en or product.name_ar)
    tokens = []
    for token in name.split():
        if token in STOP_TOKENS:
            continue
        if token == brand:
            continue
        if token.isdigit():
            continue
        if len(token) <= 1:
            continue
        tokens.append(token)
    return tokens


def derive_identity(product: ExtractedProduct) -> ProductIdentity:
    text = " ".join(filter(None, [product.name_en, product.name_ar, product.brand]))
    normalized_brand = _brand(product)
    size_value, size_unit = _extract_size(text)
    pack_count = _extract_pack_count(text)
    variant = _variant(text)

    # Cross-barcode auto-linking is deliberately strict. Brand and explicit size are mandatory.
    # Name/variant fingerprint is additional protection against regular/diet/flavor collisions.
    if not normalized_brand or size_value is None or size_unit is None:
        return ProductIdentity(
            identity_key=None,
            variant=variant,
            net_content_value=size_value,
            net_content_unit=size_unit,
            pack_count=pack_count,
            confidence=0.0,
            method="isolated_missing_identity_fields",
        )

    name_tokens = _name_tokens(product, normalized_brand)
    # Keep a compact stable product-family signal. If no useful product token remains,
    # do not merge automatically even when brand and size match.
    family_tokens = sorted(set(name_tokens))[:6]
    if not family_tokens and variant is None:
        return ProductIdentity(
            identity_key=None,
            variant=variant,
            net_content_value=size_value,
            net_content_unit=size_unit,
            pack_count=pack_count,
            confidence=0.0,
            method="isolated_ambiguous_family",
        )

    raw = "|".join(
        [
            normalized_brand,
            variant or "standard",
            f"{size_value.normalize()}:{size_unit}",
            str(pack_count),
            ",".join(family_tokens),
        ]
    )
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]
    confidence = 0.96 if variant else 0.92
    return ProductIdentity(
        identity_key=f"v1:{digest}",
        variant=variant,
        net_content_value=size_value,
        net_content_unit=size_unit,
        pack_count=pack_count,
        confidence=confidence,
        method="brand_variant_size_pack_family",
    )
