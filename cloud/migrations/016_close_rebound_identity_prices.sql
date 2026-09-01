-- If the parser later learns a better canonical identity for the exact same retailer
-- source URL, close the old open price period immediately instead of leaving a stale
-- second current offer behind.

create or replace function private.close_rebound_identity_price()
returns trigger
language plpgsql
security definer
set search_path = public, private
as $$
begin
    if old.canonical_product_id is distinct from new.canonical_product_id then
        update public.identity_price_periods
        set valid_to = coalesce(new.last_checked_at, now()),
            last_seen_at = greatest(last_seen_at, coalesce(new.last_checked_at, now()))
        where canonical_product_id = old.canonical_product_id
          and retailer_id = old.retailer_id
          and branch_key = old.branch_key
          and valid_to is null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_close_rebound_identity_price on public.retailer_identity_sources;
create trigger trg_close_rebound_identity_price
after update of canonical_product_id on public.retailer_identity_sources
for each row
when (old.canonical_product_id is distinct from new.canonical_product_id)
execute function private.close_rebound_identity_price();

revoke all on function private.close_rebound_identity_price() from public, anon, authenticated;
