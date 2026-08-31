# Saudi supermarket barcode price cloud

This folder contains the zero-cost-first backend for the standalone Android app **مقارن الأسعار**.

## Runtime goal

A scan must never scrape retailer websites. Expensive work happens before the user scans:

1. Catalog discovery finds retailer product pages/public or authorized feeds and accepts a product only when a GTIN/barcode can be verified.
2. Price refresh runs every 12 hours.
3. Supabase builds one compact `product_snapshots` JSON record per scanned barcode.
4. Equivalent barcodes for the same conservative canonical product are precomputed into that same snapshot.
5. Only changed snapshots are replicated to Cloudflare D1 and Firebase Realtime Database.
6. Android races all configured clouds in parallel and can show a Room-cached result immediately.

The system never invents a barcode, product identity or supermarket price.

## Retailer targets

Configured in `cloud/ingest/retailers.json`:

1. Carrefour Saudi Arabia
2. LuLu Hypermarket Saudi Arabia
3. Danube
4. BinDawood
5. Tamimi Markets
6. Panda
7. Abdullah Al Othaim Markets
8. Farm Superstores
9. Spinneys Saudi Arabia
10. AlRaya Supermarkets

A retailer-specific bulk connector is preferred whenever a public/authorized catalog feed exists. The generic HTML fallback is deliberately strict: it accepts only an unambiguous valid GTIN exposed by the product page and respects `robots.txt`. Private/authenticated endpoints are not required by this project.

## Three-cloud layout

### 1. Supabase — canonical data

Supabase stores normalized products, canonical identities, barcode aliases, retailer source mappings, compact price periods, 30-day history and the denormalized `product_snapshots` read model.

Android reads only `product_snapshots.barcode` + `product_snapshots.payload` using the anon/read-only key. The service-role credential is backend-only. `product_price_snapshot` remains a compatibility/read view but is not on the Android critical path.

If a product is first seen with incomplete metadata it receives an isolated identity. Migration `003_identity_upgrade.sql` allows that unverified isolated identity to be promoted or safely linked later when brand + variant + size + pack produce a high-confidence identity. Verified/manual aliases are never moved automatically.

### 2. Cloudflare D1 + Worker — fastest read replica

D1 stores:

- one indexed JSON snapshot per barcode;
- small system-state metadata;
- a compact `missing_barcodes` priority queue for unknown scans.

Worker routes:

```text
GET /health
GET /v1/products/{barcode}
```

Lookup order is edge cache -> one indexed D1 row. An unknown barcode may use Open Food Facts for name/image metadata only, with a strict short timeout. Whether metadata is found or not, the unknown scan is queued asynchronously; queueing never blocks the response. Repeated scans increment `scan_count` rather than creating duplicates.

### 3. Firebase Realtime Database — failover read replica

Firebase stores only the hot snapshots plus the tiny refresh-health document:

```text
/product_snapshots/{barcode}
/system_state/last_price_refresh
```

Client writes are blocked by `cloud/firebase.database.rules.json`. Replica writes use backend service credentials.

## Local Android cache

Room is a fourth local layer, not a cloud. Previously scanned products can appear immediately while D1/Supabase/Firebase are queried in parallel. A metadata-only response is never allowed to overwrite a cached priced snapshot.

## Exact barcode then canonical product

Runtime lookup is always by the scanned barcode first. Snapshot building then embeds offers from equivalent barcodes that belong to the same conservative canonical product. The main price therefore prefers the exact barcode, while comparison rows can include alternate barcodes for the same size/type/pack without a second network request.

## Response contract

```json
{
  "barcode": "6281000000000",
  "canonical_product_id": 123,
  "matched_barcodes": ["6281000000000", "6281000000001"],
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
  "confidence": 1.0,
  "offers": [
    {
      "retailer": "Carrefour",
      "price": 11.95,
      "currency": "SAR",
      "updated_at": "2026-08-30T09:00:00Z",
      "branch_key": "online",
      "source_url": "https://...",
      "barcode": "6281000000001"
    }
  ],
  "product_info": {
    "manufacturing_country": "...",
    "ingredients": "...",
    "allergens": [],
    "positive_notes": [],
    "caution_notes": []
  }
}
```

`offers` is sorted by price in the Android UI. `min_30d` and `max_30d` are calculated only from captured observations in the rolling 30-day window.

## Scheduled jobs

### Every 12 hours — `.github/workflows/price-refresh.yml`

- exports up to 250 high-priority unknown scans from D1;
- persists useful unknown-barcode metadata to Supabase;
- refreshes known retailer sources;
- rebuilds canonical snapshots;
- generates a changed-only replica delta;
- syncs Firebase when configured;
- applies changed snapshots to D1;
- marks queued barcodes resolved only after a real supermarket price/offer exists;
- isolates retailer/cloud failures so healthy sources continue.

### Weekly discovery — `.github/workflows/catalog-discovery.yml`

Broad discovery is deliberately separate from runtime lookup. It gradually discovers new product sources and rejects pages without a directly verifiable valid GTIN.

## Initial Supabase setup

For a fresh database apply these files in this order in the Supabase SQL editor:

```text
1. cloud/postgres.sql
2. cloud/canonical_identity.sql
3. cloud/product_info.sql
4. cloud/canonical_fast_lookup.sql
5. cloud/migrations/003_identity_upgrade.sql
```

`cloud/migrations/002_ingest_functions.sql` is a legacy migration only; do **not** apply it to a fresh database after the canonical schema. Migration 003 removes its old overloaded RPC signature when upgrading an older database.

## D1 / Firebase / Worker setup

Apply D1 schema:

```text
cloud/d1.sql
```

The 12-hour workflow also runs the idempotent D1 schema before queue/snapshot operations so additive tables are created automatically.

Deploy Firebase rules from:

```text
cloud/firebase.database.rules.json
```

Deploy Worker source from:

```text
cloud/worker/index.js
```

with a D1 binding named `DB`.

## Android read configuration

Only public/read-only build values belong in the APK:

```text
SUPABASE_URL=
SUPABASE_ANON_KEY=
CLOUDFLARE_PRODUCTS_URL=
FIREBASE_DATABASE_URL=
```

Never put Supabase service-role credentials, Firebase service-account JSON or Cloudflare API tokens inside the Android app.

## GitHub Actions backend secrets

```text
SUPABASE_URL
SUPABASE_SERVICE_ROLE_KEY
FIREBASE_DATABASE_URL
FIREBASE_SERVICE_ACCOUNT_JSON
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_D1_DATABASE_NAME
```

## Stable Android signing secrets

To make every newer APK install **over** the previous version without uninstalling it, all installable builds/releases must use one permanent signing key:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

The package id is fixed to `com.malik05255.supermarket`, so it installs beside the original VibeApp. Version codes increase automatically in CI. Never commit the signing keystore to this public repository.

## Accuracy rules

- Validate GTIN-8/12/13/14 check digits before accepting catalog data.
- Never join a retailer page to a barcode using title similarity alone.
- Canonical cross-barcode matching requires conservative brand/variant/size/pack evidence.
- On refresh, verify the mapped barcode is still present on the page before accepting its price.
- Preserve retailer, branch/region when available, source URL and observed time.
- Do not keep an old price alive when a source stops exposing a valid current price.
- Prefer retailer-hosted product images; use trusted metadata fallback only for identity/image gaps.
- Use GS1-authorized data only when access/licensing permits it.
- Respect retailer access controls, terms, rate limits and `robots.txt`.

## Zero-cost constraint

The architecture has no required paid dependency and is designed around free tiers plus public-repository GitHub Actions. Free tiers are quota-limited, not unlimited. Changed-only replication, compact price periods, D1 edge caching, Room caching, priority queues and separate discovery are used specifically to minimize quota consumption.

No public/free source can guarantee every barcode sold in Saudi Arabia or real-time parity with every physical branch. When data cannot be verified, the app returns unavailable/missing data rather than manufacturing a result.
