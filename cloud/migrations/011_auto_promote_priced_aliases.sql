-- Automatically apply the conservative unique-priced-identity promotion whenever
-- a barcode alias is inserted or materially refreshed by an ingestion source.
-- The promotion function itself refuses verified aliases and ambiguous candidates.

create or replace function private.auto_promote_priced_identity_alias()
returns trigger
language plpgsql
security definer
set search_path = public, private
as $$
begin
    -- promote_barcode_to_unique_priced_identity updates product_aliases itself.
    -- Prevent the resulting UPDATE from recursively re-running promotion.
    if pg_trigger_depth() > 1 or coalesce(new.verified, false) then
        return new;
    end if;

    perform public.promote_barcode_to_unique_priced_identity(new.barcode);
    return new;
end;
$$;

drop trigger if exists product_aliases_auto_promote_priced_identity
on public.product_aliases;

create trigger product_aliases_auto_promote_priced_identity
after insert or update of canonical_product_id, match_method, match_confidence, verified
on public.product_aliases
for each row
execute function private.auto_promote_priced_identity_alias();

revoke all on function private.auto_promote_priced_identity_alias()
from public, anon, authenticated;
