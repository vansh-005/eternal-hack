create role oms_app    login password 'oms';
create role nocook_app login password 'nocook';

create schema oms    authorization oms_app;
create schema nocook authorization nocook_app;

revoke all on schema oms    from public;
revoke all on schema nocook from public;
-- deliberately no grant on schema oms to nocook_app
