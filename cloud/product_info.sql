-- Product information enrichment stored once per canonical product.
-- This data is included in the hot snapshot; opening the Android dialog performs no network call.

create or replace function upsert_product_info(
    p_barcode text,
    p_product_info jsonb
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_canonical_id bigint;
begin
    if p_product_info is null or p_product_info = '{}'::jsonb then
        return;
    end if;

    select canonical_product_id into v_canonical_id
    from product_aliases
    where barcode = p_barcode;

    if v_canonical_id is null then
        raise exception 'Barcode has no canonical identity: %', p_barcode;
    end if;

    update canonical_products
    set product_info = case
            when product_info is null then p_product_info
            else product_info || p_product_info
        end,
        updated_at = now()
    where id = v_canonical_id;
end;
$$;

revoke all on function upsert_product_info(text, jsonb) from public, anon, authenticated;
