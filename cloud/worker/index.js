const BARCODE_RE = /^[0-9]{8,14}$/;
const OFF_FIELDS = "code,product_name,product_name_ar,product_name_en,image_front_url,image_url,brands";
const METADATA_TIMEOUT_MS = 1200;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method !== "GET") {
      return json({ error: "method_not_allowed" }, 405);
    }

    if (url.pathname === "/health") {
      try {
        await env.DB.prepare("SELECT 1 AS ok").first();
        const state = await env.DB
          .prepare("SELECT value, updated_at FROM system_state WHERE key = 'last_price_refresh' LIMIT 1")
          .first();
        return json(
          {
            ok: true,
            last_price_refresh: state?.value ? JSON.parse(state.value) : null,
            state_updated_at: state?.updated_at || null
          },
          200,
          { "Cache-Control": "no-store" }
        );
      } catch {
        return json({ ok: false }, 503, { "Cache-Control": "no-store" });
      }
    }

    const match = url.pathname.match(/^\/v1\/products\/([0-9]{8,14})$/);
    if (!match || !BARCODE_RE.test(match[1])) {
      return json({ error: "not_found" }, 404);
    }

    const barcode = match[1];
    const cache = caches.default;
    const cacheKey = new Request(`${url.origin}/v1/products/${barcode}`, request);
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    try {
      const row = await env.DB
        .prepare("SELECT payload, updated_at FROM product_snapshots WHERE barcode = ? LIMIT 1")
        .bind(barcode)
        .first();

      if (row?.payload) {
        const payload = JSON.parse(row.payload);
        const response = json(
          { ...payload, cloud_source: "cloudflare_d1" },
          200,
          {
            "Cache-Control": "public, max-age=300, stale-while-revalidate=21600",
            "X-Snapshot-Changed-At": row.updated_at || ""
          }
        );
        ctx.waitUntil(cache.put(cacheKey, response.clone()));
        return response;
      }
    } catch {
      // The Android client is racing Supabase and Firebase at the same time.
      return json({ error: "cloud_unavailable" }, 503, { "Cache-Control": "no-store" });
    }

    // Metadata-only fallback for a new barcode. It is intentionally time-bounded so an
    // external metadata service can never become the critical path for supermarket prices.
    const metadata = await lookupOpenFoodFacts(barcode);
    ctx.waitUntil(recordMissingBarcode(env, barcode, metadata));

    if (!metadata) {
      return json({ error: "product_not_found", barcode }, 404, {
        "Cache-Control": "public, max-age=120"
      });
    }

    const response = json(
      {
        barcode,
        name_ar: metadata.nameAr,
        name_en: metadata.nameEn,
        image_url: metadata.imageUrl,
        current_price: null,
        currency: "SAR",
        retailer: null,
        price_updated_at: null,
        min_30d: null,
        max_30d: null,
        source_count: 0,
        confidence: 0.6,
        offers: [],
        cloud_source: "open_food_facts_metadata"
      },
      200,
      { "Cache-Control": "public, max-age=900, stale-while-revalidate=21600" }
    );
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  }
};

async function recordMissingBarcode(env, barcode, metadata) {
  try {
    await env.DB.prepare(
      `INSERT INTO missing_barcodes (
         barcode, first_seen_at, last_seen_at, scan_count, name_ar, name_en, image_url, status
       ) VALUES (?, datetime('now'), datetime('now'), 1, ?, ?, ?, 'pending')
       ON CONFLICT(barcode) DO UPDATE SET
         last_seen_at = excluded.last_seen_at,
         scan_count = missing_barcodes.scan_count + 1,
         name_ar = COALESCE(missing_barcodes.name_ar, excluded.name_ar),
         name_en = COALESCE(missing_barcodes.name_en, excluded.name_en),
         image_url = COALESCE(missing_barcodes.image_url, excluded.image_url),
         status = CASE WHEN missing_barcodes.status = 'ignored' THEN 'ignored' ELSE 'pending' END`
    )
      .bind(
        barcode,
        metadata?.nameAr || null,
        metadata?.nameEn || null,
        metadata?.imageUrl || null
      )
      .run();
  } catch {
    // Queueing is best-effort and must never delay/fail the user's barcode lookup.
  }
}

async function lookupOpenFoodFacts(barcode) {
  try {
    const endpoint = new URL(`https://world.openfoodfacts.org/api/v2/product/${barcode}.json`);
    endpoint.searchParams.set("fields", OFF_FIELDS);
    const response = await fetch(endpoint.toString(), {
      headers: { "User-Agent": "SaudiSupermarketBarcode/1.0 (product metadata fallback)" },
      signal: AbortSignal.timeout(METADATA_TIMEOUT_MS)
    });
    if (!response.ok) return null;
    const data = await response.json();
    if (!data?.product) return null;

    const p = data.product;
    const nameAr = nonEmpty(p.product_name_ar);
    const nameEn = nonEmpty(p.product_name_en) || nonEmpty(p.product_name);
    const imageUrl = nonEmpty(p.image_front_url) || nonEmpty(p.image_url);
    if (!nameAr && !nameEn && !imageUrl) return null;

    return { nameAr, nameEn, imageUrl };
  } catch {
    return null;
  }
}

function nonEmpty(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...extraHeaders
    }
  });
}
