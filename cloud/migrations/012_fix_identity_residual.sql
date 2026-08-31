-- Live upgrade for databases that already applied migration 010 before its token-by-token fix.
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
