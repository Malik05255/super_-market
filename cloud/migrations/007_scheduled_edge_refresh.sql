-- Autonomous free-tier refresh path: pg_cron -> pg_net -> protected Edge Functions.
-- Required Vault names (never commit their plaintext values):
--   supermarket_project_url
--   supermarket_publishable_key
--   supermarket_ingest_cron_token

create extension if not exists pg_cron;
create extension if not exists pg_net;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create or replace function private.invoke_supermarket_ingest(
    p_function text,
    p_retailer text,
    p_max_pages integer default 20
) returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_project_url text;
    v_publishable text;
    v_token text;
    v_request_id bigint;
begin
    if p_function not in ('supermarket-ingest', 'supermarket-identity-ingest') then
        raise exception 'Unsupported ingest function: %', p_function;
    end if;
    if p_retailer not in (
        'carrefour','lulu','danube','bindawood','tamimi',
        'panda','othaim','farm','spinneys','alraya'
    ) then
        raise exception 'Unsupported retailer: %', p_retailer;
    end if;

    select decrypted_secret into v_project_url
    from vault.decrypted_secrets where name = 'supermarket_project_url';
    select decrypted_secret into v_publishable
    from vault.decrypted_secrets where name = 'supermarket_publishable_key';
    select decrypted_secret into v_token
    from vault.decrypted_secrets where name = 'supermarket_ingest_cron_token';

    if v_project_url is null or v_publishable is null or v_token is null then
        raise exception 'Supermarket ingestion Vault configuration is incomplete';
    end if;

    select net.http_post(
        url := rtrim(v_project_url, '/') || '/functions/v1/' || p_function,
        headers := jsonb_build_object(
            'Content-Type','application/json',
            'apikey',v_publishable,
            'Authorization','Bearer ' || v_publishable,
            'x-ingest-token',v_token
        ),
        body := jsonb_build_object(
            'retailer',p_retailer,
            'max_pages',greatest(1,least(coalesce(p_max_pages,20),20))
        ),
        timeout_milliseconds := 120000
    ) into v_request_id;

    return v_request_id;
end;
$$;

revoke all on function private.invoke_supermarket_ingest(text,text,integer)
from public, anon, authenticated;

-- Idempotently replace only this application's scheduled jobs.
do $$
declare r record;
begin
    for r in select jobid from cron.job where jobname like 'supermarket-%' loop
        perform cron.unschedule(r.jobid);
    end loop;
end $$;

-- UTC. Riyadh = UTC+3, so these run roughly 03:02-03:49 and 15:02-15:49.
select cron.schedule('supermarket-lulu-gtin',        '2 0,12 * * *', $$select private.invoke_supermarket_ingest('supermarket-ingest','lulu',20);$$);
select cron.schedule('supermarket-lulu-identity',    '4 0,12 * * *', $$select private.invoke_supermarket_ingest('supermarket-identity-ingest','lulu',20);$$);
select cron.schedule('supermarket-carrefour-gtin',   '7 0,12 * * *', $$select private.invoke_supermarket_ingest('supermarket-ingest','carrefour',20);$$);
select cron.schedule('supermarket-carrefour-identity','9 0,12 * * *', $$select private.invoke_supermarket_ingest('supermarket-identity-ingest','carrefour',20);$$);
select cron.schedule('supermarket-danube-gtin',      '12 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','danube',20);$$);
select cron.schedule('supermarket-danube-identity',  '14 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','danube',20);$$);
select cron.schedule('supermarket-bindawood-gtin',   '17 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','bindawood',20);$$);
select cron.schedule('supermarket-bindawood-identity','19 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','bindawood',20);$$);
select cron.schedule('supermarket-tamimi-gtin',      '22 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','tamimi',20);$$);
select cron.schedule('supermarket-tamimi-identity',  '24 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','tamimi',20);$$);
select cron.schedule('supermarket-panda-gtin',       '27 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','panda',20);$$);
select cron.schedule('supermarket-panda-identity',   '29 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','panda',20);$$);
select cron.schedule('supermarket-othaim-gtin',      '32 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','othaim',20);$$);
select cron.schedule('supermarket-othaim-identity',  '34 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','othaim',20);$$);
select cron.schedule('supermarket-farm-gtin',        '37 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','farm',20);$$);
select cron.schedule('supermarket-farm-identity',    '39 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','farm',20);$$);
select cron.schedule('supermarket-spinneys-gtin',    '42 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','spinneys',20);$$);
select cron.schedule('supermarket-spinneys-identity','44 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','spinneys',20);$$);
select cron.schedule('supermarket-alraya-gtin',      '47 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-ingest','alraya',20);$$);
select cron.schedule('supermarket-alraya-identity',  '49 0,12 * * *',$$select private.invoke_supermarket_ingest('supermarket-identity-ingest','alraya',20);$$);
