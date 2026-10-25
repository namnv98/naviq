-- Dữ liệu mẫu cho sqlctx-postgres (db naviq, user tester). Chạy lại được nhiều lần.
--   public: users/orders/products/contracts, giống bên Oracle (user NAVIQ)
--   shop:   schema có khoá ngoại, view, function - để thử gợi ý theo schema/alias/join

-- ===== public =====
drop table if exists public.orders, public.users, public.products, public.contracts cascade;

create table public.users (
    id    integer primary key,
    name  varchar(100),
    email varchar(200)
);

create table public.orders (
    id          integer primary key,
    customer_id integer,
    total       numeric(12, 2),
    status      varchar(20),
    user_id     integer references public.users (id)
);

create table public.products (
    id          integer primary key,
    name        varchar(100),
    price       numeric(12, 2),
    quantity    integer,
    description varchar(500)
);

create table public.contracts (
    id     integer primary key,
    name   varchar(100),
    amount numeric(14, 2),
    status varchar(20)
);

insert into public.users values
    (1, 'An', 'an@example.com'), (2, 'Binh', 'binh@example.com'), (3, 'Chi', 'chi@example.com'),
    (4, 'Dung', 'dung@example.com'), (5, 'Em', 'em@example.com');
insert into public.orders values
    (1, 1, 120.50, 'PAID', 1), (2, 2, 75.00, 'NEW', 2), (3, 1, 310.00, 'SHIPPED', 1),
    (4, 3, 15.99, 'CANCELLED', 3), (5, 4, 980.00, 'PAID', 4), (6, 5, 42.00, 'NEW', 5);
insert into public.products values
    (1, 'Keyboard', 49.90, 120, 'Mechanical keyboard'), (2, 'Mouse', 19.90, 300, 'Wireless mouse'),
    (3, 'Monitor', 229.00, 40, '27 inch IPS'), (4, 'Headset', 89.00, 75, null),
    (5, 'Webcam', 59.00, 60, '1080p'), (6, 'USB hub', 25.00, 200, '7 ports');
insert into public.contracts values
    (1, 'Maintenance 2026', 12000, 'ACTIVE'), (2, 'Cloud hosting', 54000, 'ACTIVE'),
    (3, 'Legacy support', 3000, 'EXPIRED'), (4, 'Consulting', 8000, 'DRAFT');

-- ===== shop =====
drop schema if exists shop cascade;
create schema shop;

create type shop.order_status as enum ('new', 'paid', 'shipped', 'delivered', 'cancelled');

create table shop.categories (
    id        integer generated always as identity primary key,
    name      varchar(100) not null unique,
    parent_id integer references shop.categories (id)
);

create table shop.products (
    id          integer generated always as identity primary key,
    sku         varchar(20) not null unique,
    name        varchar(200) not null,
    category_id integer not null references shop.categories (id),
    price       numeric(12, 2) not null check (price >= 0),
    stock       integer not null default 0,
    attributes  jsonb,
    is_active   boolean not null default true,
    created_at  timestamptz not null default now()
);

create table shop.customers (
    id         integer generated always as identity primary key,
    uuid       uuid not null default gen_random_uuid(),
    full_name  varchar(200) not null,
    email      varchar(200) not null unique,
    phone      varchar(20),
    city       varchar(100),
    birth_date date,
    created_at timestamptz not null default now()
);

create table shop.orders (
    id          integer generated always as identity primary key,
    customer_id integer not null references shop.customers (id),
    status      shop.order_status not null default 'new',
    ordered_at  timestamptz not null,
    shipped_at  timestamptz,
    note        text
);
create index orders_customer_idx on shop.orders (customer_id);

create table shop.order_items (
    order_id   integer not null references shop.orders (id) on delete cascade,
    product_id integer not null references shop.products (id),
    quantity   integer not null check (quantity > 0),
    unit_price numeric(12, 2) not null,
    primary key (order_id, product_id)
);

create table shop.payments (
    id       integer generated always as identity primary key,
    order_id integer not null references shop.orders (id),
    method   varchar(20) not null,
    amount   numeric(12, 2) not null,
    paid_at  timestamptz not null
);

insert into shop.categories (name, parent_id) values
    ('Electronics', null), ('Books', null), ('Home', null);
insert into shop.categories (name, parent_id) values
    ('Laptops', 1), ('Phones', 1), ('Accessories', 1), ('Fiction', 2), ('Kitchen', 3);

insert into shop.products (sku, name, category_id, price, stock, attributes)
select 'SKU-' || lpad(g::text, 4, '0'),
       'Product ' || g,
       4 + (g % 5),
       round((5 + random() * 995)::numeric, 2),
       (random() * 500)::int,
       jsonb_build_object('color', (array['black', 'white', 'red', 'blue'])[1 + g % 4], 'weight_g', 100 + g * 7)
from generate_series(1, 50) g;

insert into shop.customers (full_name, email, phone, city, birth_date)
select 'Customer ' || g,
       'customer' || g || '@example.com',
       '09' || lpad((g * 7919 % 100000000)::text, 8, '0'),
       (array['Ha Noi', 'Ho Chi Minh', 'Da Nang', 'Hai Phong', 'Can Tho'])[1 + g % 5],
       date '1970-01-01' + (g * 211 % 15000)
from generate_series(1, 200) g;

insert into shop.orders (customer_id, status, ordered_at, shipped_at)
select 1 + (g * 37 % 200),
       (enum_range(null::shop.order_status))[1 + g % 5],
       timestamptz '2026-01-01' + g * interval '3 hours',
       case when g % 5 in (2, 3) then timestamptz '2026-01-02' + g * interval '3 hours' end
from generate_series(1, 1000) g;

insert into shop.order_items (order_id, product_id, quantity, unit_price)
select o.id, p.id, 1 + (o.id + p.id) % 4, p.price
from shop.orders o
join shop.products p on (o.id * 7 + p.id) % 17 = 0;

insert into shop.payments (order_id, method, amount, paid_at)
select o.id,
       (array['cash', 'card', 'bank', 'wallet'])[1 + o.id % 4],
       sum(i.quantity * i.unit_price),
       o.ordered_at + interval '10 minutes'
from shop.orders o
join shop.order_items i on i.order_id = o.id
where o.status <> 'new' and o.status <> 'cancelled'
group by o.id, o.ordered_at;

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

create function shop.customer_total(p_customer_id integer) returns numeric
language sql stable as $$
    select coalesce(sum(i.quantity * i.unit_price), 0)
    from shop.orders o
    join shop.order_items i on i.order_id = o.id
    where o.customer_id = p_customer_id and o.status <> 'cancelled'
$$;

analyze;
