-- Current production scope: automate LuLu and Tamimi only.
-- Other retailer definitions/data remain intact for future reactivation.

do $$
declare r record;
begin
    for r in
        select jobid
        from cron.job
        where jobname like 'supermarket-%'
          and jobname not in (
              'supermarket-lulu-gtin',
              'supermarket-lulu-identity',
              'supermarket-tamimi-gtin',
              'supermarket-tamimi-identity'
          )
    loop
        perform cron.unschedule(r.jobid);
    end loop;
end $$;
