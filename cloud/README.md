# Saudi supermarket barcode price cloud

This folder contains the zero-cost-first backend for the standalone Android app **مقارن الأسعار**.

## Runtime goal

A scan must never scrape retailer websites. Expensive work happens before the user scans:

1. Catalog discovery finds public/authorized retailer product pages and accepts a barcode association only when the GTIN can be verified unambiguously.
2. A small discovery pass plus price refresh runs every 12 hours; a broader discovery pass runs weekly.
3. Supabase builds one compact `product_snapshots` JSON record per scanned barcode.
4. Equivalent barcodes for the same conservative canonical product are precomputed into that same snapshot.
5. Only changed snapshots are replicated to Cloudflare D1 and Firebase Realtime Database when those replicas are configured.
6. Android races every configured cloud in parallel and can show its simple local snapshot cache immediately.

The system never invents a barcode, product identity, supermarket price or manufacturing country.

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

A retailer-specific bulk connector is preferred whenever a public/authorized catalog feed exists. The generic HTML fallback is deliberately strict: it accepts only an unambiguous valid GTIN exposed by the product page and respects `robots.txt`. Product links can be discovered from sitemaps, normal HTML links and serialized storefront state. Private/authenticated endpoints are not required.

## Three-cloud layout

### 1. Supabase — canonical data and live primary read source

The live project used by the Android client is:

```text
https://lbgcjmsqqhrpceijdqng.supabase.co
```

Supabase stores normalized products, canonical identities, barcode aliases, retailer source mappings, compact price periods, 30-day history and the denormalized `product_snapshots` read model.

Android reads only `product_snapshots` and the tiny `system_state` health document using a publishable key. Raw price history, retailer-source mappings, canonical identity tables and every write RPC are inaccessible to `anon`/`authenticated`. Backend writes are service-role-only.

If a product is first seen with incomplete metadata it receives an isolated identity. Migration `003_identity_upgrade.sql` allows that unverified isolated identity to be promoted or safely linked later when brand + variant + size + pack produce a high-confidence identity. Verified/manual aliases are never moved automatically.

### 2. Cloudflare D1 + Worker — edge read replica

D1 stores one indexed JSON snapshot per barcode, small system-state metadata and a compact `missing_barcodes` priority queue for unknown scans.

Worker routes:

```text
GET /health
GET /v1/products/{barcode}
```

Lookup order is edge cache -> one indexed D1 row. An unknown barcode may use Open Food Facts for name/image metadata only, with a strict short timeout. Whether metadata is found or not, the unknown scan is queued asynchronously; queueing never blocks the response. Repeated scans increment `scan_count` rather than creating duplicates.

### 3. Firebase Realtime Database — optional failover read replica

Firebase stores only hot snapshots plus the tiny refresh-health document:

```text
/product_snapshots/{barcode}
/system_state/last_price_refresh
```

Client writes are blocked by `cloud/firebase.database.rules.json`. Replica writes use backend service credentials.

## Local Android cache

The standalone `market-app` deliberately avoids the old VibeApp Room/build-engine stack. It keeps a lightweight device-local snapshot cache keyed by barcode. A cached result can render first while network sources race in parallel, and a metadata-only network response is not allowed to replace a fuller priced result.

## Exact barcode then canonical product

Runtime lookup is always by the scanned barcode first. Snapshot building embeds offers from equivalent barcodes that belong to the same conservative canonical product. The headline price therefore prefers the exact barcode when available, while comparison rows can include alternate barcodes for the same size/type/pack without a second network request.

The live Supabase schema has been verified with a two-barcode test: the exact barcode kept its own retailer price as the headline while the cheaper alternate-barcode retailer offer appeared in the same `offers` array. Test rows were deleted after verification.

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

- runs ingestion safety tests first;
- performs a small new-product discovery pass (up to 40 new pages per retailer);
- exports high-priority unknown scans from D1 when D1 is configured;
- refreshes known retailer sources;
- rebuilds canonical snapshots;
- generates a changed-only replica delta;
- syncs Firebase when configured;
- applies changed snapshots to D1 when configured;
- isolates retailer/cloud failures so healthy sources continue.

This prevents a fresh database from remaining empty until the weekly discovery job.

### Weekly discovery — `.github/workflows/catalog-discovery.yml`

A broader discovery pass gradually expands coverage. It reads sitemap declarations from `robots.txt`, conventional sitemaps, robots-allowed catalog seed pages and serialized storefront links. Pages without a directly verifiable valid GTIN are rejected.

> GitHub scheduled workflows execute from the repository default branch. While these workflow files exist only on a feature branch/PR, their cron schedules do not run automatically.

## Fresh Supabase setup

The live project has already been provisioned. For a future fresh database, use this order:

```text
1. cloud/postgres.sql
2. DROP VIEW IF EXISTS public.product_price_snapshot;
3. cloud/canonical_identity.sql
4. cloud/migrations/003_identity_upgrade.sql
5. cloud/product_info.sql
6. cloud/canonical_fast_lookup.sql
7. cloud/migrations/004_security_hardening.sql
```

The explicit `DROP VIEW` before the canonical upgrade is required because PostgreSQL does not permit `CREATE OR REPLACE VIEW` to insert/reorder columns of an existing view. `004_security_hardening.sql` makes the view `security_invoker`, restricts frontend access to the hot read model, locks all write RPCs to `service_role`, and adds the barcode FK covering index recommended by the Supabase performance advisor.

`cloud/migrations/002_ingest_functions.sql` is a legacy migration only; do **not** apply it to a fresh database after the canonical schema.

## D1 / Firebase / Worker setup

Apply D1 schema:

```text
cloud/d1.sql
```

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

The live Supabase URL and publishable key are already safe defaults in `market-app/build.gradle.kts`. Environment/Gradle values override them only when non-blank.

Optional extra read replicas use:

```text
CLOUDFLARE_PRODUCTS_URL=
FIREBASE_DATABASE_URL=
```

Never put Supabase service-role credentials, Firebase service-account JSON or Cloudflare API tokens inside the Android app.

## GitHub Actions backend secrets

Only one secret is required to enable the canonical Supabase discovery/price writer:

```text
SUPABASE_SERVICE_ROLE_KEY
```

The Supabase URL is public configuration and is already set in the workflows. Optional replicas additionally use:

```text
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
- Bind a GTIN to the specific JSON-LD Product object that contains it; a barcode appearing elsewhere on a recommendation-heavy page is not sufficient.
- Never join a retailer page to a barcode using title similarity alone.
- Canonical cross-barcode matching requires conservative brand/variant/size/pack evidence.
- On refresh, verify the mapped barcode is still present on the product before accepting its price.
- Preserve retailer, branch/region when available, source URL and observed time.
- Do not keep an old price alive when a source stops exposing a valid current price.
- Prefer retailer-hosted product images and product information when verified; use trusted metadata fallback only for identity/image gaps.
- Use GS1-authorized data only when access/licensing permits it.
- Respect retailer access controls, terms, rate limits and `robots.txt`.

## Zero-cost constraint

The architecture has no required paid dependency and is designed around free tiers plus public-repository GitHub Actions. Free tiers are quota-limited, not unlimited. Changed-only replication, compact price periods, edge caching, lightweight local caching, priority queues and separate discovery are used specifically to minimize quota consumption.

No public/free source can guarantee every barcode sold in Saudi Arabia or real-time parity with every physical branch. When data cannot be verified, the app returns unavailable/missing data rather than manufacturing a result.
