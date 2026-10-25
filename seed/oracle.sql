-- Dữ liệu mẫu cho sqlctx-oracle (PDB FREEPDB1). Chạy bằng SYSTEM, chạy lại được nhiều lần.
-- Tạo user SHOP (khoá ngoại, view, function), cấp quyền đọc/ghi cho NAVIQ để thử gợi ý
-- "shop.<bảng>" từ context oradev. Các bảng sẵn có của NAVIQ (users/orders/...) giữ nguyên.
whenever sqlerror exit failure

drop user if exists shop cascade;
create user shop identified by Shop123 quota unlimited on users;
grant create session, create table, create view, create procedure, create sequence to shop;

create table shop.categories (
    id        number generated always as identity primary key,
    name      varchar2(100) not null unique,
    parent_id number references shop.categories (id)
);

create table shop.products (
    id          number generated always as identity primary key,
    sku         varchar2(20) not null unique,
    name        varchar2(200) not null,
    category_id number not null references shop.categories (id),
    price       number(12, 2) not null check (price >= 0),
    stock       number(10) default 0 not null,
    attributes  json,
    is_active   boolean default true not null,
    created_at  timestamp with time zone default systimestamp not null
);

create table shop.customers (
    id         number generated always as identity primary key,
    guid       raw(16) default sys_guid() not null,
    full_name  varchar2(200) not null,
    email      varchar2(200) not null unique,
    phone      varchar2(20),
    city       varchar2(100),
    birth_date date,
    created_at timestamp with time zone default systimestamp not null
);

create table shop.orders (
    id          number generated always as identity primary key,
    customer_id number not null references shop.customers (id),
    status      varchar2(10) default 'new' not null
                check (status in ('new', 'paid', 'shipped', 'delivered', 'cancelled')),
    ordered_at  timestamp with time zone not null,
    shipped_at  timestamp with time zone,
    note        clob
);
create index shop.orders_customer_idx on shop.orders (customer_id);

create table shop.order_items (
    order_id   number not null references shop.orders (id) on delete cascade,
    product_id number not null references shop.products (id),
    quantity   number(5) not null check (quantity > 0),
    unit_price number(12, 2) not null,
    primary key (order_id, product_id)
);

create table shop.payments (
    id       number generated always as identity primary key,
    order_id number not null references shop.orders (id),
    method   varchar2(20) not null,
    amount   number(12, 2) not null,
    paid_at  timestamp with time zone not null
);

insert into shop.categories (name, parent_id)
select 'Electronics', null from dual union all
select 'Books', null from dual union all
select 'Home', null from dual;
insert into shop.categories (name, parent_id)
select 'Laptops', 1 from dual union all
select 'Phones', 1 from dual union all
select 'Accessories', 1 from dual union all
select 'Fiction', 2 from dual union all
select 'Kitchen', 3 from dual;

insert into shop.products (sku, name, category_id, price, stock, attributes)
select 'SKU-' || lpad(level, 4, '0'),
       'Product ' || level,
       4 + mod(level, 5),
       round(5 + dbms_random.value * 995, 2),
       trunc(dbms_random.value * 500),
       json_object('color' value decode(mod(level, 4), 0, 'black', 1, 'white', 2, 'red', 'blue'),
                   'weight_g' value 100 + level * 7)
from dual connect by level <= 50;

insert into shop.customers (full_name, email, phone, city, birth_date)
select 'Customer ' || level,
       'customer' || level || '@example.com',
       '09' || lpad(mod(level * 7919, 100000000), 8, '0'),
       decode(mod(level, 5), 0, 'Ha Noi', 1, 'Ho Chi Minh', 2, 'Da Nang', 3, 'Hai Phong', 'Can Tho'),
       date '1970-01-01' + mod(level * 211, 15000)
from dual connect by level <= 200;

insert into shop.orders (customer_id, status, ordered_at, shipped_at)
select 1 + mod(level * 37, 200),
       decode(mod(level, 5), 0, 'new', 1, 'paid', 2, 'shipped', 3, 'delivered', 'cancelled'),
       timestamp '2026-01-01 00:00:00 +07:00' + numtodsinterval(level * 3, 'HOUR'),
       case when mod(level, 5) in (2, 3)
            then timestamp '2026-01-02 00:00:00 +07:00' + numtodsinterval(level * 3, 'HOUR') end
from dual connect by level <= 1000;

insert into shop.order_items (order_id, product_id, quantity, unit_price)
select o.id, p.id, 1 + mod(o.id + p.id, 4), p.price
from shop.orders o
join shop.products p on mod(o.id * 7 + p.id, 17) = 0;

insert into shop.payments (order_id, method, amount, paid_at)
select o.id,
       decode(mod(o.id, 4), 0, 'cash', 1, 'card', 2, 'bank', 'wallet'),
       sum(i.quantity * i.unit_price),
       o.ordered_at + interval '10' minute
from shop.orders o
join shop.order_items i on i.order_id = o.id
where o.status not in ('new', 'cancelled')
group by o.id, o.ordered_at;

commit;

create view shop.order_summary as
select o.id as order_id,
       c.full_name as customer_name,
       o.status,
       o.ordered_at,
       count(i.product_id) as item_count,
       coalesce(sum(i.quantity * i.unit_price), 0) as total
from shop.orders o
join shop.customers c on c.id = o.customer_id
left join shop.order_items i on i.order_id = o.id
group by o.id, c.full_name, o.status, o.ordered_at;

create or replace function shop.customer_total(p_customer_id number) return number is
    v_total number;
begin
    select coalesce(sum(i.quantity * i.unit_price), 0)
    into v_total
    from shop.orders o
    join shop.order_items i on i.order_id = o.id
    where o.customer_id = p_customer_id and o.status <> 'cancelled';
    return v_total;
end;
/

begin
    for t in (select table_name from all_tables where owner = 'SHOP') loop
        execute immediate 'grant select, insert, update, delete on shop.' || t.table_name || ' to naviq';
    end loop;
end;
/
grant select on shop.order_summary to naviq;
grant execute on shop.customer_total to naviq;

exec dbms_stats.gather_schema_stats('SHOP');
