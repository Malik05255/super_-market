-- Allows an initially metadata-only / isolated barcode to be upgraded later when
-- a conservative high-confidence identity key becomes available. Verified/manual
-- aliases are never moved automatically.

create or replace function upsert_product_metadata(
    p_barcode text,
    p_name_ar text default null,
    p_name_en text default null,
    p_brand text default null,
    p_image_url text default null,
    p_gs1_verified boolean default false,
    p_identity_key text default null,
    p_variant text default null,
    p_net_content_value numeric default null,
    p_net_content_unit text default null,
    p_pack_count integer default 1,
    p_match_confidence double precision default 1.0,
    p_match_method text default 'barcode'
) returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_canonical_id bigint;
    v_target_id bigint;
    v_existing_method text;
    v_existing_verified boolean;
    v_existing_identity_key text;
    v_alias_count integer;
    v_identity_key text := nullif(trim(coalesce(p_identity_key, '')), '');
    v_match_confidence double precision := greatest(0.0, least(coalesce(p_match_confidence, 1.0), 1.0));
begin
    if p_barcode !~ '^[0-9]{8,14}$' then
        raise exception 'Invalid barcode: %', p_barcode;
    end if;

    insert into products (barcode, name_ar, name_en, brand, image_url, gs1_verified, updated_at)
    values (p_barcode, p_name_ar, p_name_en, p_brand, p_image_url, p_gs1_verified, now())
    on conflict (barcode) do update
    set name_ar = coalesce(excluded.name_ar, products.name_ar),
        name_en = coalesce(excluded.name_en, products.name_en),
        brand = coalesce(excluded.brand, products.brand),
        image_url = coalesce(excluded.image_url, products.image_url),
        gs1_verified = products.gs1_verified or excluded.gs1_verified,
        updated_at = now();

    select
        pa.canonical_product_id,
        pa.match_method,
        pa.verified,
        cp.identity_key
    into
        v_canonical_id,
        v_existing_method,
        v_existing_verified,
        v_existing_identity_key
    from product_aliases pa
    join canonical_products cp on cp.id = pa.canonical_product_id
    where pa.barcode = p_barcode;

    if v_canonical_id is not null then
        -- Only isolated, non-verified identities are eligible for automatic promotion.
        if v_identity_key is not null
           and not coalesce(v_existing_verified, false)
           and coalesce(v_existing_method, '') in (
               'barcode',
               'single_barcode',
               'legacy_isolated',
               'isolated_missing_identity_fields',
               'isolated_ambiguous_family'
           ) then

            select id into v_target_id
            from canonical_products
            where identity_key = v_identity_key;

            if v_target_id is not null and v_target_id <> v_canonical_id then
                -- A trusted canonical family already exists: move only this unverified alias.
                update product_aliases
                set canonical_product_id = v_target_id,
                    match_method = coalesce(nullif(p_match_method, ''), 'identity_upgrade'),
                    match_confidence = v_match_confidence,
                    verified = p_gs1_verified,
                    updated_at = now()
                where barcode = p_barcode
                  and verified = false;
                v_canonical_id := v_target_id;

            elsif v_target_id is null and v_existing_identity_key is null then
                -- If the isolated canonical contains only this barcode, promote it in place.
                select count(*) into v_alias_count
                from product_aliases
                where canonical_product_id = v_canonical_id;

                if v_alias_count = 1 then
                    update canonical_products
                    set identity_key = v_identity_key,
                        updated_at = now()
                    where id = v_canonical_id
                      and identity_key is null;
                end if;
            end if;
        end if;

        update canonical_products
        set canonical_name_ar = coalesce(canonical_name_ar, p_name_ar),
            canonical_name_en = coalesce(canonical_name_en, p_name_en),
            brand = coalesce(brand, p_brand),
            variant = coalesce(variant, p_variant),
            net_content_value = coalesce(net_content_value, p_net_content_value),
            net_content_unit = coalesce(net_content_unit, p_net_content_unit),
            pack_count = greatest(pack_count, coalesce(p_pack_count, 1)),
            image_url = coalesce(image_url, p_image_url),
            updated_at = now()
        where id = v_canonical_id;

        update product_aliases
        set match_method = case
                when verified then match_method
                when v_identity_key is not null then coalesce(nullif(p_match_method, ''), match_method)
                else match_method
            end,
            match_confidence = case
                when verified then match_confidence
                else greatest(match_confidence, v_match_confidence)
            end,
            verified = verified or p_gs1_verified,
            updated_at = now()
        where barcode = p_barcode;

        return v_canonical_id;
    end if;

    if v_identity_key is not null then
        select id into v_canonical_id
        from canonical_products
        where identity_key = v_identity_key;
    end if;

    if v_canonical_id is null then
        insert into canonical_products (
            canonical_name_ar, canonical_name_en, brand, variant,
            net_content_value, net_content_unit, pack_count, image_url, identity_key
        ) values (
            p_name_ar, p_name_en, p_brand, p_variant,
            p_net_content_value, p_net_content_unit, greatest(coalesce(p_pack_count, 1), 1),
            p_image_url, v_identity_key
        )
        returning id into v_canonical_id;
    end if;

    insert into product_aliases (
        barcode, canonical_product_id, match_method, match_confidence, verified, updated_at
    ) values (
        p_barcode,
        v_canonical_id,
        coalesce(nullif(p_match_method, ''), 'barcode'),
        v_match_confidence,
        p_gs1_verified,
        now()
    );

    return v_canonical_id;
end;
$$;
