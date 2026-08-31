-- Conservative bridge between exact scanned barcodes and retailer identity-only prices.
-- Used when an external barcode source knows brand/variant/size/pack but retailer pages do
-- not expose a GTIN. Promotion is allowed only when exactly one recent priced canonical
-- identity has the same structured fields AND the normalized residual product name matches.

create schema if not exists private;

create or replace function private.identity_residual(p_name text, p_brand text)
returns text
language sql
immutable
set search_path = public, private
as $$
    select trim(
        regexp_replace(
            regexp_replace(
                replace(lower(coalesce(p_name, '')), lower(coalesce(p_brand, '')), ' '),
                '(^|[^[:alnum:]\u0600-\u06ff])(diet|zero|light|original|regular|classic|cola|can|cans|bottle|bottles|pack|packs|piece|pieces|ml|ltr|liter|litre|gram|gm|kg|kilogram|دايت|زيرو|لايت|كولا|علبة|علب|عبوة|عبوات|زجاجة|زجاجات|حبة|حبات|مل|لتر|جرام|غرام|كيلو|كجم|[0-9]+([.,][0-9]+)?)([^[:alnum:]\u0600-\u06ff]|$)',
                ' ',
                'gi'
            ),
            '[^[:alnum:]\u0600-\u06ff]+',
            ' ',
            'g'
        )
    );
$$;

create or replace function public.promote_barcode_to_unique_priced_identity(p_barcode text)
returns bigint
language plpgsql
security definer
set search_path = public, private
as $$
declare
    v_source canonical_products%rowtype;
    v_source_verified boolean;
    v_source_name text;
    v_source_residual text;
    v_candidates bigint[];
    v_target bigint;
begin
    select cp.*, pa.verified
    into v_source, v_source_verified
    from product_aliases pa
    join canonical_products cp on cp.id = pa.canonical_product_id
    where pa.barcode = p_barcode;

    if v_source.id is null or coalesce(v_source_verified, false) then
        return null;
    end if;

    if nullif(trim(coalesce(v_source.brand, '')), '') is null
       or v_source.net_content_value is null
       or nullif(trim(coalesce(v_source.net_content_unit, '')), '') is null then
        return null;
    end if;

    v_source_name := coalesce(v_source.canonical_name_en, v_source.canonical_name_ar, '');
    v_source_residual := private.identity_residual(v_source_name, v_source.brand);

    select array_agg(candidate_id order by candidate_id)
    into v_candidates
    from (
        select cp.id as candidate_id
        from canonical_products cp
        where cp.id <> v_source.id
          and lower(trim(coalesce(cp.brand, ''))) = lower(trim(v_source.brand))
          and cp.variant is not distinct from v_source.variant
          and cp.net_content_value = v_source.net_content_value
          and lower(trim(coalesce(cp.net_content_unit, ''))) = lower(trim(v_source.net_content_unit))
          and cp.pack_count = v_source.pack_count
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
    set canonical_name_ar = coalesce(target.canonical_name_ar, v_source.canonical_name_ar),
        canonical_name_en = coalesce(target.canonical_name_en, v_source.canonical_name_en),
        image_url = coalesce(target.image_url, v_source.image_url),
        product_info = case
            when v_source.product_info is null then target.product_info
            when target.product_info is null then v_source.product_info
            else target.product_info || v_source.product_info
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
