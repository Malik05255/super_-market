-- Merge exact-barcode and canonical-identity retailer evidence into one hot snapshot.
-- Exact barcode evidence always wins the scanned barcode headline when available.

create or replace function public.rebuild_product_snapshots()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count integer;
begin
    with all_current as (
        select
            pa.canonical_product_id,
            pp.barcode as observed_barcode,
            pp.retailer_id,
            pp.price,
            pp.currency,
            pp.branch_key,
            pp.source_url,
            pp.valid_from as price_changed_at,
            pp.last_seen_at,
            r.name_ar as retailer,
            'barcode'::text as match_method,
            2 as evidence_rank
        from public.price_periods pp
        join public.product_aliases pa on pa.barcode = pp.barcode
        join public.retailers r on r.id = pp.retailer_id
        where pp.last_seen_at >= now() - interval '36 hours'

        union all

        select
            ip.canonical_product_id,
            null::text as observed_barcode,
            ip.retailer_id,
            ip.price,
            ip.currency,
            ip.branch_key,
            ip.source_url,
            ip.valid_from as price_changed_at,
            ip.last_seen_at,
            r.name_ar as retailer,
            'canonical_identity'::text as match_method,
            1 as evidence_rank
        from public.identity_price_periods ip
        join public.retailers r on r.id = ip.retailer_id
        where ip.last_seen_at >= now() - interval '36 hours'
    ), canonical_latest_per_store as (
        select distinct on (canonical_product_id, retailer_id, branch_key)
            canonical_product_id, observed_barcode, retailer_id, price, currency,
            branch_key, source_url, price_changed_at, last_seen_at, retailer, match_method
        from all_current
        order by canonical_product_id, retailer_id, branch_key, last_seen_at desc, evidence_rank desc
    ), canonical_offers as (
        select canonical_product_id,
            jsonb_agg(
                jsonb_build_object(
                    'retailer', retailer,
                    'price', price,
                    'currency', currency,
                    'updated_at', price_changed_at,
                    'branch_key', branch_key,
                    'source_url', source_url,
                    'barcode', observed_barcode,
                    'match_method', match_method
                ) order by price asc, retailer asc
            ) as offers,
            count(*)::int as source_count
        from canonical_latest_per_store
        group by canonical_product_id
    ), canonical_latest_any as (
        select distinct on (canonical_product_id)
            canonical_product_id, observed_barcode, retailer, price, currency,
            price_changed_at, last_seen_at, match_method
        from canonical_latest_per_store
        order by canonical_product_id, last_seen_at desc, (observed_barcode is not null) desc
    ), exact_latest_per_barcode as (
        select distinct on (pp.barcode)
            pp.barcode, pp.price, pp.currency,
            pp.valid_from as price_changed_at, pp.last_seen_at, r.name_ar as retailer
        from public.price_periods pp
        join public.retailers r on r.id = pp.retailer_id
        where pp.last_seen_at >= now() - interval '36 hours'
        order by pp.barcode, pp.last_seen_at desc
    ), all_history as (
        select pa.canonical_product_id, pp.price, pp.valid_from, pp.valid_to, pp.last_seen_at
        from public.price_periods pp
        join public.product_aliases pa on pa.barcode = pp.barcode
        union all
        select ip.canonical_product_id, ip.price, ip.valid_from, ip.valid_to, ip.last_seen_at
        from public.identity_price_periods ip
    ), canonical_stats as (
        select canonical_product_id, min(price) as min_30d, max(price) as max_30d
        from all_history
        where coalesce(valid_to, last_seen_at) >= now() - interval '30 days'
          and valid_from <= now()
        group by canonical_product_id
    ), alias_sets as (
        select canonical_product_id,
            jsonb_agg(barcode order by barcode) as matched_barcodes
        from public.product_aliases
        group by canonical_product_id
    ), canonical_metadata as (
        select cp.id as canonical_product_id,
            coalesce(cp.canonical_name_ar, meta.name_ar) as name_ar,
            coalesce(cp.canonical_name_en, meta.name_en) as name_en,
            coalesce(cp.image_url, meta.image_url) as image_url,
            cp.product_info,
            coalesce(o.offers, '[]'::jsonb) as offers,
            coalesce(o.source_count, 0) as source_count,
            s.min_30d, s.max_30d,
            coalesce(a.matched_barcodes, '[]'::jsonb) as matched_barcodes,
            ca.price as canonical_price,
            coalesce(ca.currency, 'SAR') as canonical_currency,
            ca.retailer as canonical_retailer,
            ca.price_changed_at as canonical_price_changed_at,
            ca.last_seen_at as canonical_last_seen_at,
            ca.match_method as canonical_match_method
        from public.canonical_products cp
        left join lateral (
            select p.name_ar, p.name_en, p.image_url
            from public.product_aliases pa2
            join public.products p on p.barcode = pa2.barcode
            where pa2.canonical_product_id = cp.id
            order by (p.image_url is not null) desc,
                     (p.name_ar is not null or p.name_en is not null) desc,
                     p.updated_at desc
            limit 1
        ) meta on true
        left join canonical_offers o on o.canonical_product_id = cp.id
        left join canonical_stats s on s.canonical_product_id = cp.id
        left join alias_sets a on a.canonical_product_id = cp.id
        left join canonical_latest_any ca on ca.canonical_product_id = cp.id
    ), built_per_scanned_barcode as (
        select pa.barcode,
            jsonb_build_object(
                'barcode', pa.barcode,
                'canonical_product_id', cm.canonical_product_id,
                'matched_barcodes', cm.matched_barcodes,
                'name_ar', cm.name_ar,
                'name_en', cm.name_en,
                'image_url', cm.image_url,
                'current_price', coalesce(ex.price, cm.canonical_price),
                'currency', coalesce(ex.currency, cm.canonical_currency, 'SAR'),
                'retailer', coalesce(ex.retailer, cm.canonical_retailer),
                'price_updated_at', coalesce(ex.price_changed_at, cm.canonical_price_changed_at),
                'exact_barcode_match', (ex.barcode is not null),
                'headline_match_method', case
                    when ex.barcode is not null then 'exact_barcode'
                    else cm.canonical_match_method
                end,
                'min_30d', cm.min_30d,
                'max_30d', cm.max_30d,
                'source_count', cm.source_count,
                'confidence', case
                    when ex.last_seen_at >= now() - interval '14 hours' then 1.0
                    when ex.last_seen_at >= now() - interval '26 hours' then 0.95
                    when cm.canonical_last_seen_at >= now() - interval '14 hours' then 0.9
                    when cm.canonical_last_seen_at >= now() - interval '26 hours' then 0.82
                    when cm.canonical_last_seen_at is not null then 0.7
                    else 0.5
                end,
                'offers', cm.offers,
                'product_info', cm.product_info
            ) as payload
        from public.product_aliases pa
        join canonical_metadata cm on cm.canonical_product_id = pa.canonical_product_id
        left join exact_latest_per_barcode ex on ex.barcode = pa.barcode
    )
    insert into public.product_snapshots (barcode, payload, payload_hash, updated_at)
    select barcode, payload, md5(payload::text), now()
    from built_per_scanned_barcode
    on conflict (barcode) do update
    set payload = excluded.payload,
        payload_hash = excluded.payload_hash,
        updated_at = excluded.updated_at
    where public.product_snapshots.payload_hash is distinct from excluded.payload_hash;

    get diagnostics v_count = row_count;

    insert into public.system_state (key, value, updated_at)
    values (
        'last_price_refresh',
        jsonb_build_object(
            'completed_at', now(),
            'changed_snapshots', v_count,
            'lookup_strategy', 'exact_barcode_then_canonical_identity_precomputed'
        ), now()
    )
    on conflict (key) do update
    set value = excluded.value,
        updated_at = excluded.updated_at;

    delete from public.price_periods
    where valid_to is not null and valid_to < now() - interval '35 days';
    delete from public.identity_price_periods
    where valid_to is not null and valid_to < now() - interval '35 days';

    return v_count;
end;
$$;

revoke all on function public.rebuild_product_snapshots() from public, anon, authenticated;
grant execute on function public.rebuild_product_snapshots() to service_role;
