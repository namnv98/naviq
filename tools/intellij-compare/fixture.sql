-- Schema fixture của test completion (khớp CompletionFixtures.installPostgres()) trên Postgres thật,
-- để IntelliJ introspect và gợi ý trên CÙNG dữ liệu với tool.
-- Chạy: docker exec -i sqlctx-postgres psql -U tester -d naviq -v ON_ERROR_STOP=1 < tools/intellij-compare/fixture.sql

drop database if exists sqlctx_fixture;
create database sqlctx_fixture;

-- Role trong fixture test: app_reader, app_writer, postgres (image docker chỉ tạo sẵn "tester").
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_reader') then create role app_reader; end if;
    if not exists (select from pg_roles where rolname = 'app_writer') then create role app_writer; end if;
    if not exists (select from pg_roles where rolname = 'postgres') then create role postgres; end if;
end
$$;

\c sqlctx_fixture

create table users (id int4 primary key, name text, email text);
create table orders (id int4, customer_id int4, total numeric, status text, user_id int4);
create table contracts (id int4, name text, amount numeric, status text);
create table products (id int4, name text, price numeric, quantity int4, description text);
create view active_users as select id, name from users;
create materialized view daily_totals as select id, total from orders;
