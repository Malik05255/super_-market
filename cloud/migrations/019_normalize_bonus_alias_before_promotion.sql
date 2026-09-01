-- Central safety net for barcode metadata sources (SFDA/OFF/future resolvers).
-- A source may expose a promotional quantity such as "750 + 75 G" while its
-- parser reports only the trailing 75 g. Correct that structured size before the
-- conservative priced-identity promotion runs. Never touch verified aliases or
-- canonical rows already shared by multiple barcodes.

create or replace function private.normalize_bonus_alias_size(p_barcode text)
returns boolean
language plpgsql
security definer
set search_path = public, private
as $$
declare
    v_canonical_id bigint;
    v_verified boolean;
    v_alias_count integer;
    v_name text;
    v_match text[];
    v_first numeric;
    v_second numeric;
    v_first_unit text;
    v_second_unit text;
    v_base_unit text;
    v_first_multiplier numeric;
    v_second_multiplier numeric;
    v_total numeric;
begin
    select pa.canonical_product_id, pa.verified,
           coalesce(p.name_en, p.name_ar, cp.canonical_name_en, cp.canonical_name_ar, '')
    into v_canonical_id, v_verified, v_name
    from public.product_aliases pa
    join public.products p on p.barcode = pa.barcode
    join public.canonical_products cp on cp.id = pa.canonical_product_id
    where pa.barcode = p_barcode;

    if v_canonical_id is null or coalesce(v_verified, false) then
        return false;
    end if;

    select count(*) into v_alias_count
    from public.product_aliases
    where canonical_product_id = v_canonical_id;

    if v_alias_count <> 1 then
        return false;
    end if;

    v_match := regexp_match(
        lower(v_name),
        '([0-9]+([.,][0-9]+)?)\s*(ml|l|ltr|liter|litre|g|gm|gram|kg|مل|لتر|جرام|غرام|كيلو|كجم)?\s*\+\s*([0-9]+([.,][0-9]+)?)\s*(ml|l|ltr|liter|litre|g|gm|gram|kg|مل|لتر|جرام|غرام|كيلو|كجم)'
    );

    if v_match is null then
        return false;
    end if;

    v_first := replace(v_match[1], ',', '.')::numeric;
    v_first_unit := nullif(v_match[3], '');
    v_second := replace(v_match[4], ',', '.')::numeric;
    v_second_unit := v_match[6];
    v_first_unit := coalesce(v_first_unit, v_second_unit);

    if v_first <= 0 or v_second <= 0 then
        return false;
    end if;

    case v_first_unit
        when 'ml' then v_base_unit := 'ml'; v_first_multiplier := 1;
        when 'مل' then v_base_unit := 'ml'; v_first_multiplier := 1;
        when 'l' then v_base_unit := 'ml'; v_first_multiplier := 1000;
        when 'ltr' then v_base_unit := 'ml'; v_first_multiplier := 1000;
        when 'liter' then v_base_unit := 'ml'; v_first_multiplier := 1000;
        when 'litre' then v_base_unit := 'ml'; v_first_multiplier := 1000;
        when 'لتر' then v_base_unit := 'ml'; v_first_multiplier := 1000;
        when 'g' then v_base_unit := 'g'; v_first_multiplier := 1;
        when 'gm' then v_base_unit := 'g'; v_first_multiplier := 1;
        when 'gram' then v_base_unit := 'g'; v_first_multiplier := 1;
        when 'جرام' then v_base_unit := 'g'; v_first_multiplier := 1;
        when 'غرام' then v_base_unit := 'g'; v_first_multiplier := 1;
        when 'kg' then v_base_unit := 'g'; v_first_multiplier := 1000;
        when 'كيلو' then v_base_unit := 'g'; v_first_multiplier := 1000;
        when 'كجم' then v_base_unit := 'g'; v_first_multiplier := 1000;
        else return false;
    end case;

    case v_second_unit
        when 'ml' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1;
        when 'مل' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1;
        when 'l' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1000;
        when 'ltr' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1000;
        when 'liter' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1000;
        when 'litre' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1000;
        when 'لتر' then if v_base_unit <> 'ml' then return false; end if; v_second_multiplier := 1000;
        when 'g' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1;
        when 'gm' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1;
        when 'gram' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1;
        when 'جرام' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1;
        when 'غرام' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1;
        when 'kg' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1000;
        when 'كيلو' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1000;
        when 'كجم' then if v_base_unit <> 'g' then return false; end if; v_second_multiplier := 1000;
        else return false;
    end case;

    v_total := round(v_first * v_first_multiplier + v_second * v_second_multiplier, 3);
    if v_total <= 0 or v_total > 100000 then
        return false;
    end if;

    update public.canonical_products
    set net_content_value = v_total,
        net_content_unit = v_base_unit,
        identity_key = null,
        updated_at = now()
    where id = v_canonical_id;

    return true;
end;
$$;

revoke all on function private.normalize_bonus_alias_size(text)
from public, anon, authenticated;

create or replace function private.auto_promote_priced_identity_alias()
returns trigger
language plpgsql
security definer
set search_path = public, private
as $$
begin
    if pg_trigger_depth() > 1 or coalesce(new.verified, false) then
        return new;
    end if;

    perform private.normalize_bonus_alias_size(new.barcode);
    perform public.promote_barcode_to_unique_priced_identity(new.barcode);
    return new;
end;
$$;

revoke all on function private.auto_promote_priced_identity_alias()
from public, anon, authenticated;
