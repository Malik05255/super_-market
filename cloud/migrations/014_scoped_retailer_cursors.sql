-- Production scope: LuLu + Tamimi only.
-- Keep all retailer rows for future reactivation, but make only the current two active.
update public.retailers
set active = (slug in ('lulu','tamimi'));

-- Persistent scan cursors let an Edge collector advance through a large public catalog
-- instead of retrying the same rejected first page set every 12 hours.
create table if not exists public.retailer_scan_cursors (
    retailer_id bigint not null references public.retailers(id) on delete cascade,
    stream text not null,
    cursor_position bigint not null default 0 check (cursor_position >= 0),
    catalog_size bigint,
    updated_at timestamptz not null default now(),
    primary key (retailer_id, stream)
);

alter table public.retailer_scan_cursors enable row level security;
revoke all on table public.retailer_scan_cursors from public, anon, authenticated;
grant select, insert, update, delete on table public.retailer_scan_cursors to service_role;

create index if not exists retailer_scan_cursors_updated_idx
    on public.retailer_scan_cursors(updated_at desc);
