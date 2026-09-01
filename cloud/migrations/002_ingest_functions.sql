create or replace function upsert_product_metadata(
    p_barcode text,
    p_name_ar text default null,
    p_name_en text default null,
    p_brand text default null,
    p_image_url text default null,
    p_gs1_verified boolean default false
) returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if p_barcode !~ '^[0-9]{8,14}$' then
        raise exception 'Invalid barcode';
    end if;

    insert into products (
        barcode, name_ar, name_en, brand, image_url, gs1_verified, updated_at
    ) values (
        p_barcode,
        nullif(p_name_ar, ''),
        nullif(p_name_en, ''),
        nullif(p_brand, ''),
        nullif(p_image_url, ''),
        p_gs1_verified,
        now()
    )
    on conflict (barcode) do update
    set name_ar = coalesce(nullif(excluded.name_ar, ''), products.name_ar),
        name_en = coalesce(nullif(excluded.name_en, ''), products.name_en),
        brand = coalesce(nullif(excluded.brand, ''), products.brand),
        image_url = coalesce(nullif(excluded.image_url, ''), products.image_url),
        gs1_verified = products.gs1_verified or excluded.gs1_verified,
        updated_at = case
            when products.name_ar is distinct from coalesce(nullif(excluded.name_ar, ''), products.name_ar)
              or products.name_en is distinct from coalesce(nullif(excluded.name_en, ''), products.name_en)
              or products.brand is distinct from coalesce(nullif(excluded.brand, ''), products.brand)
              or products.image_url is distinct from coalesce(nullif(excluded.image_url, ''), products.image_url)
              or products.gs1_verified is distinct from (products.gs1_verified or excluded.gs1_verified)
            then now()
            else products.updated_at
        end;
end;
$$;
