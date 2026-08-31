# Saudi supermarket barcode price cloud

This folder contains the zero-cost-first backend for the Android barcode scanner.

## Runtime goal

A scan must not scrape retailer websites. The expensive work happens before the user scans:

1. Catalog discovery finds retailer product pages/authorized feeds and accepts a product only when a valid GTIN/barcode can be verified directly.
2. Price refresh runs every 12 hours.
3. Supabase builds one compact `product_snapshots` JSON record per barcode.
4. Only changed snapshots are replicated to Cloudflare D1 and Firebase Realtime Database.
5. Android requests all configured clouds in parallel and can show a Room-cached result immediately while the clouds verify it.

The system never invents a barcode, product identity or price.

## Retailer targets

The initial Saudi target set is configured in `cloud/ingest/retailers.json`:

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

A retailer-specific bulk connector is preferred whenever a public/authorized catalog feed exists. The generic HTML fallback is intentionally strict: it accepts only an unambiguous valid GTIN exposed by the page and respects `robots.txt`. Private/authenticated endpoints must not be reverse-engineered into this project.

## Three-cloud layout

### 1. Supabase — canonical data

Supabase stores:

- normalized products and retailer metadata;
- exact retailer-page-to-barcode mappings;
- compact price periods rather than duplicate rows every 12 hours;
- 30-day price history;
- the canonical denormalized `product_snapshots` read model.

If a retailer reports the same price for several refreshes, only `last_seen_at` changes. A new history row is created only when the price changes.

The Android client has read-only access to `product_price_snapshot`; service-role credentials are backend-only.

### 2. Cloudflare D1 + Worker — fastest read replica

D1 stores only:

- one JSON snapshot row per barcode;
- small system-state metadata.

The Worker exposes:

```text
GET /health
GET /v1/products/{barcode}
```

The product route uses Cloudflare edge cache first, then a single indexed D1 row. If a barcode is not known yet, the Worker may use Open Food Facts for name/image metadata only. It never takes a supermarket price from that metadata fallback.

### 3. Firebase Realtime Database — failover read replica

Firebase stores only:

```text
/product_snapshots/{barcode}
```

Android reads the snapshot directly with one REST request. Client writes are blocked by `cloud/firebase.database.rules.json`; replica writes use backend service credentials.

## Local Android cache

Room is a fourth local layer, not a cloud. A previously scanned product can appear immediately from the phone while D1/Supabase/Firebase are queried in parallel. A metadata-only cloud result is not allowed to overwrite a previously cached priced snapshot.

## Response contract

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
  "confidence": 1.0,
  "offers": [
    {
      "retailer": "Carrefour",
      "price": 11.95,
      "currency": "SAR",
      "updated_at": "2026-08-30T09:00:00Z",
      "branch_key": "online",
      "source_url": "https://..."
    }
  ]
}
```

`offers` is sorted by price in the Android UI. `min_30d` and `max_30d` are calculated only from captured observations in the rolling 30-day window. `price_updated_at` means the time that price period began (the last observed price change), not a fabricated modification time.

## Scheduled jobs

### Every 12 hours

`.github/workflows/price-refresh.yml`

- refreshes known retailer sources;
- rebuilds canonical snapshots;
- generates a changed-only D1 delta;
- syncs changed snapshots to Firebase when configured;
- applies changed snapshots to D1 when configured;
- isolates failures so one retailer does not stop healthy retailers.

### Weekly discovery

`.github/workflows/catalog-discovery.yml`

Discovery is deliberately separate from price lookup/refresh. It discovers new product sources gradually and refuses pages without a directly verifiable valid GTIN.

## Initial database setup

Apply to Supabase, in order:

```text
cloud/postgres.sql
cloud/migrations/002_ingest_functions.sql
```

Apply to D1:

```text
cloud/d1.sql
```

Deploy Firebase Realtime Database rules from:

```text
cloud/firebase.database.rules.json
```

Deploy the Worker source from:

```text
cloud/worker/index.js
```

with a D1 binding named `DB`.

## Android read configuration

Set these as Gradle properties/environment variables when building the APK:

```text
SUPABASE_URL=
SUPABASE_ANON_KEY=
CLOUDFLARE_PRODUCTS_URL=
FIREBASE_DATABASE_URL=
```

Only public/read-only values belong in the Android build. Never put Supabase service-role credentials, Firebase service-account JSON, or Cloudflare API tokens into the APK.

## GitHub Actions secrets

The scheduled backend uses these repository secrets:

```text
SUPABASE_URL
SUPABASE_SERVICE_ROLE_KEY
FIREBASE_DATABASE_URL
FIREBASE_SERVICE_ACCOUNT_JSON
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_D1_DATABASE_NAME
```

If a replica's secrets are absent, that replica is skipped safely instead of breaking the canonical refresh.

## Accuracy rules

- Validate GTIN-8/12/13/14 check digits before accepting a barcode.
- Never join a retailer page to a barcode using title/size similarity alone.
- On refresh, verify the mapped barcode is still present on the page before accepting its price.
- Preserve retailer, branch/region when available, source URL and observed time.
- Do not keep an old price alive when a source stops exposing a valid current price.
- Prefer retailer-hosted product images; use trusted metadata fallback only for identity/image gaps.
- Use GS1-authorized data only when access/licensing permits it.
- Respect retailer access controls, terms, rate limits and `robots.txt`.

## Zero-cost constraint

The architecture has no required paid dependency and is designed around free tiers plus public-repository GitHub Actions. Free tiers are quota-limited, not unlimited. Changed-only replication, compact price periods, D1 edge caching, Room caching and separate discovery are specifically used to keep normal operation inside those quotas for as long as practical.

No public/free source can guarantee every barcode sold in Saudi Arabia or real-time parity with every physical branch. When data cannot be verified, the app returns unavailable/missing data rather than manufacturing a result.
