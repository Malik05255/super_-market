-- Zero-valued storefront placeholders mean unavailable/out-of-stock, not a real price.
-- Clean legacy rows and enforce the invariant in both storage and write RPCs.

update public.retailer_identity_sources
set last_error = 'zero_price_unavailable', last_checked_at = now()
where canonical_product_id in (
    select canonical_product_id
    from public.identity_price_periods
    where price <= 0
);

delete from public.identity_price_periods where price <= 0;
delete from public.price_periods where price <= 0;

alter table public.identity_price_periods
    drop constraint if exists identity_price_periods_price_check;
alter table public.identity_price_periods
    add constraint identity_price_periods_price_check check (price > 0);

alter table public.price_periods
    drop constraint if exists price_periods_price_check;
alter table public.price_periods
    add constraint price_periods_price_check check (price > 0);

create or replace function public.record_retailer_price(
    p_barcode text,
    p_retailer_slug text,
    p_branch_key text,
    p_price numeric,
    p_currency text default 'SAR',
    p_source_url text default null,
    p_observed_at timestamptz default now()
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_retailer_id bigint;
    v_current public.price_periods%rowtype;
begin
    if p_price is null or p_price <= 0 then
        raise exception 'positive price is required';
    end if;

    select id into v_retailer_id
    from public.retailers
    where slug = p_retailer_slug and active = true;
    if v_retailer_id is null then
        raise exception 'Unknown or inactive retailer: %', p_retailer_slug;
    end if;

    select * into v_current
    from public.price_periods
    where barcode = p_barcode
      and retailer_id = v_retailer_id
      and branch_key = coalesce(nullif(p_branch_key, ''), 'online')
      and valid_to is null
    for update;

    if found
       and v_current.price = p_price
       and v_current.currency = coalesce(nullif(p_currency, ''), 'SAR') then
        update public.price_periods
        set last_seen_at = p_observed_at,
            source_url = coalesce(p_source_url, source_url)
        where id = v_current.id;
        return;
    end if;

    if found then
        update public.price_periods
        set valid_to = p_observed_at,
            last_seen_at = greatest(last_seen_at, p_observed_at)
        where id = v_current.id;
    end if;

    insert into public.price_periods (
        barcode, retailer_id, branch_key, price, currency, source_url,
        valid_from, valid_to, last_seen_at
    ) values (
        p_barcode, v_retailer_id, coalesce(nullif(p_branch_key, ''), 'online'),
        p_price, coalesce(nullif(p_currency, ''), 'SAR'), p_source_url,
        p_observed_at, null, p_observed_at
    );
end;
$$;

-- Migration 005 defines record_identity_price with the same positive-price invariant.
-- Reassert permissions here because CREATE OR REPLACE can restore owner/public defaults
-- differently across PostgreSQL/Supabase versions.
revoke all on function public.record_retailer_price(
    text, text, text, numeric, text, text, timestamptz
) from public, anon, authenticated;
grant execute on function public.record_retailer_price(
    text, text, text, numeric, text, text, timestamptz
) to service_role;

revoke all on function public.record_identity_price(
    text, text, text, text, text, numeric, text, integer, text,
    text, text, numeric, text, text, timestamptz
) from public, anon, authenticated;
grant execute on function public.record_identity_price(
    text, text, text, text, text, numeric, text, integer, text,
    text, text, numeric, text, text, timestamptz
) to service_role;
