-- Harden the public Data API surface for the supermarket backend.
-- Android clients are read-only. All ingestion/RPC writes are service_role-only.

-- Cover the source->product FK; refresh and cleanup frequently filter/join by barcode.
create index if not exists retailer_product_sources_barcode_idx
on public.retailer_product_sources (barcode);

-- The app only needs hot snapshots and refresh state. Keep raw/source/history/identity
-- tables unreachable from anon/authenticated even if project default grants change.
revoke all on table public.retailers from anon, authenticated;
revoke all on table public.products from anon, authenticated;
revoke all on table public.retailer_product_sources from anon, authenticated;
revoke all on table public.price_periods from anon, authenticated;
revoke all on table public.canonical_products from anon, authenticated;
revoke all on table public.product_aliases from anon, authenticated;
revoke all on table public.product_snapshots from anon, authenticated;
revoke all on table public.system_state from anon, authenticated;

-- Explicit minimum read surface used by the Android client.
grant select on table public.product_snapshots to anon, authenticated;
grant select on table public.system_state to anon, authenticated;
grant select on table public.product_price_snapshot to anon, authenticated;

-- Views should obey the caller's privileges/RLS instead of the view owner's privileges.
alter view public.product_price_snapshot set (security_invoker = true);

-- Scope public-read policies to frontend roles only.
drop policy if exists "public read retailers" on public.retailers;
drop policy if exists "public read products" on public.products;
drop policy if exists "public read snapshots" on public.product_snapshots;
drop policy if exists "public read system state" on public.system_state;

create policy "public read snapshots"
on public.product_snapshots
for select
to anon, authenticated
using (true);

create policy "public read system state"
on public.system_state
for select
to anon, authenticated
using (true);

-- SECURITY DEFINER functions in an exposed schema must never inherit PUBLIC execute.
revoke all on function public.record_retailer_price(text, text, text, numeric, text, text, timestamptz)
from public, anon, authenticated;
revoke all on function public.rebuild_product_snapshots()
from public, anon, authenticated;
revoke all on function public.upsert_product_metadata(
    text, text, text, text, text, boolean, text, text, numeric, text, integer, double precision, text
) from public, anon, authenticated;
revoke all on function public.link_product_alias(text, bigint, text, double precision, boolean)
from public, anon, authenticated;
revoke all on function public.upsert_product_info(text, jsonb)
from public, anon, authenticated;

-- Explicit trusted backend permissions. service_role remains the only Data API writer.
grant select, insert, update, delete on table public.retailers to service_role;
grant select, insert, update, delete on table public.products to service_role;
grant select, insert, update, delete on table public.retailer_product_sources to service_role;
grant select, insert, update, delete on table public.price_periods to service_role;
grant select, insert, update, delete on table public.canonical_products to service_role;
grant select, insert, update, delete on table public.product_aliases to service_role;
grant select, insert, update, delete on table public.product_snapshots to service_role;
grant select, insert, update, delete on table public.system_state to service_role;
grant usage, select on all sequences in schema public to service_role;

grant execute on function public.record_retailer_price(text, text, text, numeric, text, text, timestamptz)
to service_role;
grant execute on function public.rebuild_product_snapshots() to service_role;
grant execute on function public.upsert_product_metadata(
    text, text, text, text, text, boolean, text, text, numeric, text, integer, double precision, text
) to service_role;
grant execute on function public.link_product_alias(text, bigint, text, double precision, boolean)
to service_role;
grant execute on function public.upsert_product_info(text, jsonb) to service_role;

notify pgrst, 'reload schema';
