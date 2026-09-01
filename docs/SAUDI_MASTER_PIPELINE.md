# Saudi Master Pipeline

Barcode flow:

1. Local cache
2. Supabase product cache
3. Saudi Master release shards
4. SFDA live resolver
5. Open Facts fallback

Rules:
- Exact GTIN matching only.
- No paid APIs in the core path.
- Product identity is separated from store pricing.
