-- Conservative bridge between exact scanned barcodes and retailer identity-only prices.
-- Used when an external barcode source knows brand/variant/size/pack but retailer pages do
-- not expose a GTIN. Promotion is allowed only when exactly one recent priced canonical
-- identity has the same structured fields AND the normalized residual product name matches.

create schema if not exists private;

create or replace function private.identity_residual(p_name text, p_brand text)
returns text
language plpgsql
immutable
set search_path = public, private
as $$
declare
    v_normalized text;
    v_token text;
    v_result text := '';
    v_stop text[] := array[
        'diet','zero','light','original','regular','classic','cola',
        'can','cans','bottle','bottles','pack','packs','piece','pieces',
        'ml','l','ltr','liter','litre','g','gm','gram','kg','kilogram',
        'دايت','زيرو','لايت','كولا','علبة','علب','عبوة','عبوات',
        'زجاجة','زجاجات','حبة','حبات','مل','لتر','جرام','غرام','كيلو','كجم'
    ];
begin
    v_normalized := lower(
        replace(
            coalesce(p_name, ''),
            coalesce(p_brand, ''),
            ' '
        )
    );
    v_normalized := regexp_replace(v_normalized, '[[:space:][:punct:]]+', ' ', 'g');

    foreach v_token in array regexp_split_to_array(trim(v_normalized), '\s+')
    loop
        if v_token is null or v_token = '' then
            continue;
        end if;
        if v_token = any(v_stop) then
            continue;
        end if;
        if v_token ~ '^[0-9]+([.,][0-9]+)?$' then
            continue;
        end if;
        if v_token ~ '^[0-9]+(ml|l|ltr|g|gm|kg)$' then
            continue;
        end if;

        v_result := concat_ws(' ', nullif(v_result, ''), v_token);
    end loop;

    return trim(v_result);
end;
$$;

create or replace function public.promote_barcode_to_unique_priced_identity(p_barcode text)
returns bigint
language plpgsql
security definer
set search_path = public, private
as $$
declare
    v_source_id bigint;
    v_source_verified boolean;
    v_source_name_ar text;
    v_source_name_en text;
    v_source_brand text;
    v_source_variant text;
    v_source_size numeric;
    v_source_unit text;
    v_source_pack integer;
    v_source_image text;
    v_source_info jsonb;
    v_source_residual text;
    v_candidates bigint[];
    v_target bigint;
begin
    select
        cp.id,
        pa.verified,
        cp.canonical_name_ar,
        cp.canonical_name_en,
        cp.brand,
        cp.variant,
        cp.net_content_value,
        cp.net_content_unit,
        cp.pack_count,
        cp.image_url,
        cp.product_info
    into
        v_source_id,
        v_source_verified,
        v_source_name_ar,
        v_source_name_en,
        v_source_brand,
        v_source_variant,
        v_source_size,
        v_source_unit,
        v_source_pack,
        v_source_image,
        v_source_info
    from product_aliases pa
    join canonical_products cp on cp.id = pa.canonical_product_id
    where pa.barcode = p_barcode;

    if v_source_id is null or coalesce(v_source_verified, false) then
        return null;
    end if;

    if nullif(trim(coalesce(v_source_brand, '')), '') is null
       or v_source_size is null
       or nullif(trim(coalesce(v_source_unit, '')), '') is null then
        return null;
    end if;

    v_source_residual := private.identity_residual(
        coalesce(v_source_name_en, v_source_name_ar, ''),
        v_source_brand
    );

    select array_agg(candidate_id order by candidate_id)
    into v_candidates
    from (
        select cp.id as candidate_id
        from canonical_products cp
        where cp.id <> v_source_id
          and lower(trim(coalesce(cp.brand, ''))) = lower(trim(v_source_brand))
          and cp.variant is not distinct from v_source_variant
          and cp.net_content_value = v_source_size
          and lower(trim(coalesce(cp.net_content_unit, ''))) = lower(trim(v_source_unit))
          and cp.pack_count = v_source_pack
          and private.identity_residual(
                coalesce(cp.canonical_name_en, cp.canonical_name_ar, ''),
                cp.brand
              ) = v_source_residual
          and exists (
              select 1
              from identity_price_periods ipp
              where ipp.canonical_product_id = cp.id
                and ipp.last_seen_at >= now() - interval '36 hours'
          )
        limit 2
    ) q;

    if coalesce(array_length(v_candidates, 1), 0) <> 1 then
        return null;
    end if;

    v_target := v_candidates[1];

    -- Preserve useful metadata learned from the exact barcode before moving its alias.
    update canonical_products target
    set canonical_name_ar = coalesce(target.canonical_name_ar, v_source_name_ar),
        canonical_name_en = coalesce(target.canonical_name_en, v_source_name_en),
        image_url = coalesce(target.image_url, v_source_image),
        product_info = case
            when v_source_info is null then target.product_info
            when target.product_info is null then v_source_info
            else target.product_info || v_source_info
        end,
        updated_at = now()
    where target.id = v_target;

    update product_aliases
    set canonical_product_id = v_target,
        match_method = 'structured_unique_priced_identity',
        match_confidence = greatest(match_confidence, 0.91),
        updated_at = now()
    where barcode = p_barcode
      and verified = false;

    return v_target;
end;
$$;

revoke all on function public.promote_barcode_to_unique_priced_identity(text)
from public, anon, authenticated;
grant execute on function public.promote_barcode_to_unique_priced_identity(text)
to service_role;
