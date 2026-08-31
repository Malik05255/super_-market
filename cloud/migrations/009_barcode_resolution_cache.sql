-- Unknown barcode resolution is an exceptional path, never part of a known scan.
-- It is rate/cooldown guarded so Open Food Facts cannot become the app's hot read path.

create table if not exists public.barcode_resolution_attempts (
    barcode text primary key,
    last_attempt_at timestamptz not null default now(),
    attempt_count integer not null default 1 check (attempt_count > 0),
    last_status text not null default 'started',
    updated_at timestamptz not null default now(),
    constraint barcode_resolution_attempts_digits check (barcode ~ '^[0-9]{8,14}$')
);

create index if not exists barcode_resolution_attempts_time_idx
    on public.barcode_resolution_attempts(last_attempt_at desc);

alter table public.barcode_resolution_attempts enable row level security;
revoke all on table public.barcode_resolution_attempts from anon, authenticated;
grant select, insert, update, delete on table public.barcode_resolution_attempts to service_role;
