# Saudi supermarket price cloud

This folder defines the server-side data contract used by the Android barcode screen.

## Goal

Maintain a normalized product catalog keyed by GTIN/barcode and a timestamped price history. The app never invents a price: it only displays observations stored by an ingestion connector, together with the observation time and retailer.

## Initial retailer targets

1. Carrefour Saudi Arabia
2. Danube
3. BinDawood
4. Panda
5. Abdullah Al Othaim Markets
6. Tamimi Markets
7. LuLu Hypermarket Saudi Arabia
8. Nesto Saudi Arabia
9. Farm Superstores
10. AlSadhan

Each retailer must be integrated through an official feed/API where available, or a permitted public catalog endpoint. Connectors must respect retailer terms, rate limits and robots/access controls. A connector must never synthesize a missing barcode or price.

## Three-cloud layout

### 1. Supabase (canonical query replica)

Stores normalized products, retailer metadata and price observations. The Android client reads the `product_price_snapshot` view through PostgREST using an anon/read-only policy.

### 2. Cloudflare D1 (low-latency replica)

Stores the same normalized keys and 30-day summary. A Worker exposes:

`GET /v1/products/{barcode}`

The response uses the JSON contract below.

### 3. Firestore (hot snapshot failover)

Stores only `product_snapshots/{barcode}` documents, not the full observation history. This keeps the free quota focused on fast barcode lookups and failover reads.

## Android response contract

```json
{
  "barcode": "6281000000000",
  "name_ar": "اسم المنتج",
  "name_en": "Product name",
  "image_url": "https://...",
  "current_price": 12.95,
  "currency": "SAR",
  "retailer": "Retailer name",
  "price_updated_at": "2026-08-31T18:40:00Z",
  "min_30d": 10.95,
  "max_30d": 14.50,
  "source_count": 6,
  "confidence": 1.0
}
```

`current_price` is the newest valid observation. `min_30d` and `max_30d` are calculated only from observations actually captured during the previous 30 days.

## Build configuration

The Android app reads these Gradle properties or environment variables:

```text
SUPABASE_URL=
SUPABASE_ANON_KEY=
CLOUDFLARE_PRODUCTS_URL=
FIRESTORE_PROJECT_ID=
FIRESTORE_API_KEY=
```

Do not commit service-role keys or write-capable tokens into the Android application.

## Ingestion rules

- Preserve the original barcode exactly after GTIN validation.
- Record every price with retailer, source URL and observation timestamp.
- Deduplicate identical observations from the same retailer within a crawl window.
- Prefer retailer-hosted product images; otherwise keep image empty until a trusted product catalog fills it.
- Keep regional/store-specific prices separate when a retailer varies prices by city or branch.
- Mark stale data; do not describe an old price as current.
- Barcode/company validation can be enriched from GS1-authorized data when licensed/available.

## Accuracy

No public/free source can guarantee complete coverage of every Saudi-market barcode or real-time prices across every physical branch. The system is designed to maximize coverage while preserving provenance and freshness, rather than returning fabricated data when a source is missing.
