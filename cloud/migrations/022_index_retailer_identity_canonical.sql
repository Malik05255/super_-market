-- Cover the canonical-product foreign key used by identity-price refresh/rebind paths.
create index if not exists retailer_identity_sources_canonical_product_idx
    on public.retailer_identity_sources (canonical_product_id);
