-- Some products contain a usage/fit range such as "Baby Diaper Size 4 8-14kg 90 pcs".
-- A naive size parser can mistake the upper bound (14 kg) for the product's net content.
-- Accuracy policy: preserve the verified retailer price, but isolate the identity so it
-- cannot be promoted across barcodes until a source provides a structurally safe identity.

create or replace function public.record_identity_price(
    p_identity_key text,
    p_name_ar text,
    p_name_en text,
    p_brand text,
    p_variant text,
    p_net_content_value numeric,
    p_net_content_unit text,
    p_pack_count integer,
    p_image_url text,
    p_retailer_slug text,
    p_branch_key text,
    p_price numeric,
    p_currency text default 'SAR',
    p_source_url text default null,
    p_observed_at timestamptz default now()
) returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_identity_key text := nullif(trim(coalesce(p_identity_key, '')), '');
    v_retailer_id bigint;
    v_canonical_id bigint;
    v_current public.identity_price_periods%rowtype;
    v_display_name text := coalesce(nullif(trim(p_name_en), ''), nullif(trim(p_name_ar), ''), '');
    v_net_content_value numeric := p_net_content_value;
    v_net_content_unit text := p_net_content_unit;
    v_quarantined boolean := false;
begin
    if p_price is null or p_price <= 0 then
        raise exception 'positive price is required';
    end if;

    -- A range immediately followed by a physical unit is generally a suitability,
    -- capacity range, adjustable range, or other non-net-content expression.
    -- Do not allow one end of the range to become a cross-product identity dimension.
    if lower(coalesce(p_net_content_unit, '')) in ('g', 'ml')
       and v_display_name ~* '[0-9]+([.,][0-9]+)?[[:space:]]*[-–—][[:space:]]*[0-9]+([.,][0-9]+)?[[:space:]]*(kg|g|ml|l)([^a-z]|$)'
    then
        v_quarantined := true;
        v_net_content_value := null;
        v_net_content_unit := null;
        v_identity_key := 'isolated-range:' || md5(
            coalesce(p_retailer_slug, '') || '|' ||
            coalesce(p_source_url, '') || '|' ||
            lower(v_display_name)
        );
    end if;

    if v_identity_key is null then
        raise exception 'identity_key is required';
    end if;

    select id into v_retailer_id
    from public.retailers
    where slug = p_retailer_slug and active = true;

    if v_retailer_id is null then
        raise exception 'Unknown or inactive retailer: %', p_retailer_slug;
    end if;

    insert into public.canonical_products (
        canonical_name_ar, canonical_name_en, brand, variant,
        net_content_value, net_content_unit, pack_count, image_url, identity_key,
        created_at, updated_at
    ) values (
        p_name_ar, p_name_en, p_brand, p_variant,
        v_net_content_value, v_net_content_unit, greatest(coalesce(p_pack_count,1),1),
        p_image_url, v_identity_key, now(), now()
    )
    on conflict (identity_key) do update
    set canonical_name_ar = coalesce(public.canonical_products.canonical_name_ar, excluded.canonical_name_ar),
        canonical_name_en = coalesce(public.canonical_products.canonical_name_en, excluded.canonical_name_en),
        brand = coalesce(public.canonical_products.brand, excluded.brand),
        variant = coalesce(public.canonical_products.variant, excluded.variant),
        -- Quarantined identities intentionally keep net content NULL even if a previous
        -- bad parse populated one. Normal identities retain the existing conservative fill.
        net_content_value = case when v_quarantined then null else coalesce(public.canonical_products.net_content_value, excluded.net_content_value) end,
        net_content_unit = case when v_quarantined then null else coalesce(public.canonical_products.net_content_unit, excluded.net_content_unit) end,
        pack_count = greatest(public.canonical_products.pack_count, excluded.pack_count),
        image_url = coalesce(public.canonical_products.image_url, excluded.image_url),
        updated_at = now()
    returning id into v_canonical_id;

    if p_source_url is not null then
        insert into public.retailer_identity_sources (
            canonical_product_id, retailer_id, identity_key, source_url, branch_key,
            active, last_checked_at, last_error
        ) values (
            v_canonical_id, v_retailer_id, v_identity_key, p_source_url,
            coalesce(nullif(p_branch_key,''),'online'), true, p_observed_at,
            case when v_quarantined then 'identity_quarantined_usage_range' else null end
        )
        on conflict (retailer_id, source_url) do update
        set canonical_product_id = excluded.canonical_product_id,
            identity_key = excluded.identity_key,
            branch_key = excluded.branch_key,
            active = true,
            last_checked_at = excluded.last_checked_at,
            last_error = excluded.last_error;
    end if;

    select * into v_current
    from public.identity_price_periods
    where canonical_product_id = v_canonical_id
      and retailer_id = v_retailer_id
      and branch_key = coalesce(nullif(p_branch_key,''),'online')
      and valid_to is null
    for update;

    if found and v_current.price = p_price
       and v_current.currency = coalesce(nullif(p_currency,''),'SAR')
    then
        update public.identity_price_periods
        set last_seen_at = p_observed_at,
            source_url = coalesce(p_source_url, source_url)
        where id = v_current.id;
        return v_canonical_id;
    end if;

    if found then
        update public.identity_price_periods
        set valid_to = p_observed_at,
            last_seen_at = greatest(last_seen_at, p_observed_at)
        where id = v_current.id;
    end if;

    insert into public.identity_price_periods (
        canonical_product_id, retailer_id, branch_key, price, currency,
        source_url, valid_from, valid_to, last_seen_at
    ) values (
        v_canonical_id, v_retailer_id, coalesce(nullif(p_branch_key,''),'online'),
        p_price, coalesce(nullif(p_currency,''),'SAR'), p_source_url,
        p_observed_at, null, p_observed_at
    );

    return v_canonical_id;
end;
$$;

-- Rebind any currently open suspicious source through the hardened function.
-- The existing source-rebind trigger closes the old wrong canonical period.
do $$
declare
    rec record;
begin
    for rec in
        select
            cp.identity_key,
            cp.canonical_name_ar,
            cp.canonical_name_en,
            cp.brand,
            cp.variant,
            cp.net_content_value,
            cp.net_content_unit,
            cp.pack_count,
            cp.image_url,
            r.slug as retailer_slug,
            ipp.branch_key,
            ipp.price,
            ipp.currency,
            ipp.source_url,
            ipp.last_seen_at
        from public.identity_price_periods ipp
        join public.canonical_products cp on cp.id = ipp.canonical_product_id
        join public.retailers r on r.id = ipp.retailer_id
        where ipp.valid_to is null
          and ipp.source_url is not null
          and lower(coalesce(cp.net_content_unit, '')) in ('g', 'ml')
          and coalesce(cp.canonical_name_en, cp.canonical_name_ar, '')
              ~* '[0-9]+([.,][0-9]+)?[[:space:]]*[-–—][[:space:]]*[0-9]+([.,][0-9]+)?[[:space:]]*(kg|g|ml|l)([^a-z]|$)'
    loop
        perform public.record_identity_price(
            rec.identity_key,
            rec.canonical_name_ar,
            rec.canonical_name_en,
            rec.brand,
            rec.variant,
            rec.net_content_value,
            rec.net_content_unit,
            rec.pack_count,
            rec.image_url,
            rec.retailer_slug,
            rec.branch_key,
            rec.price,
            rec.currency,
            rec.source_url,
            rec.last_seen_at
        );
    end loop;
end $$;

select public.rebuild_product_snapshots();
