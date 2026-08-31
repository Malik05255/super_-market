const BARCODE_RE = /^[0-9]{8,14}$/;
const OFF_FIELDS = "code,product_name,product_name_ar,product_name_en,image_front_url,image_url,brands";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method !== "GET") {
      return json({ error: "method_not_allowed" }, 405);
    }

    if (url.pathname === "/health") {
      try {
        await env.DB.prepare("SELECT 1 AS ok").first();
        return json({ ok: true }, 200, { "Cache-Control": "no-store" });
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
        .prepare("SELECT payload, updated_at, fresh_until FROM product_snapshots WHERE barcode = ? LIMIT 1")
        .bind(barcode)
        .first();

      if (row?.payload) {
        const payload = JSON.parse(row.payload);
        const response = json(
          { ...payload, cloud_source: "cloudflare_d1" },
          200,
          {
            "Cache-Control": "public, max-age=300, stale-while-revalidate=21600",
            "X-Data-Updated-At": row.updated_at || "",
            "X-Data-Fresh-Until": row.fresh_until || ""
          }
        );
        ctx.waitUntil(cache.put(cacheKey, response.clone()));
        return response;
      }
    } catch {
      // If D1 is temporarily unavailable, let the Android client race the other clouds.
      return json({ error: "cloud_unavailable" }, 503, { "Cache-Control": "no-store" });
    }

    // Metadata-only fallback for a barcode not yet present in our catalog.
    // Never invents or sources a supermarket price.
    const metadata = await lookupOpenFoodFacts(barcode);
    if (!metadata) {
      return json({ error: "product_not_found", barcode }, 404, {
        "Cache-Control": "public, max-age=300"
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
      { "Cache-Control": "public, max-age=1800, stale-while-revalidate=21600" }
    );
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  }
};

async function lookupOpenFoodFacts(barcode) {
  try {
    const endpoint = new URL(`https://world.openfoodfacts.org/api/v2/product/${barcode}.json`);
    endpoint.searchParams.set("fields", OFF_FIELDS);
    const response = await fetch(endpoint.toString(), {
      headers: { "User-Agent": "SaudiSupermarketBarcode/1.0 (product metadata fallback)" }
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
