-- Final two-store routing: one cursor collector per active retailer.

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
    if not (
        (p_retailer = 'lulu' and p_function = 'lulu-cursor-ingest')
        or
        (p_retailer = 'tamimi' and p_function = 'tamimi-cursor-ingest')
    ) then
        raise exception 'Unsupported production ingest route: % / %', p_retailer, p_function;
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
            'x-ingest-token',v_token
        ),
        body := jsonb_build_object(
            'retailer',p_retailer,
            'max_pages',greatest(4,least(coalesce(p_max_pages,20),24))
        ),
        timeout_milliseconds := 120000
    ) into v_request_id;

    return v_request_id;
end;
$$;

revoke all on function private.invoke_supermarket_ingest(text,text,integer)
from public, anon, authenticated;

do $$
declare r record;
begin
    for r in select jobid from cron.job where jobname like 'supermarket-%' loop
        perform cron.unschedule(r.jobid);
    end loop;
end $$;

select cron.schedule(
    'supermarket-lulu-identity',
    '4 0,12 * * *',
    $$select private.invoke_supermarket_ingest('lulu-cursor-ingest','lulu',20);$$
);
select cron.schedule(
    'supermarket-tamimi-identity',
    '24 0,12 * * *',
    $$select private.invoke_supermarket_ingest('tamimi-cursor-ingest','tamimi',20);$$
);
