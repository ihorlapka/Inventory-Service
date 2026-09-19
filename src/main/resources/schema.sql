CREATE TYPE order_event_type AS ENUM (
    'ORDER_CREATED',
    'ORDER_CANCELED',
    'INVENTORY_RESERVED',
    'INVENTORY_FAILED',
    'PAYMENT_COMPLETED',
    'PAYMENT_FAILED',
    'SHIPMENT_CREATED',
    'SHIPMENT_FAILED',
    'SHIPMENT_COMPLETED'
);

CREATE TYPE event_status AS ENUM ('NEW', 'PUBLISHED');

CREATE TYPE reservation_status AS ENUM (
    'RESERVED',
    'RELEASED',
    'CANCELLED'
);

CREATE TABLE products (
    id              UUID primary key default gen_random_uuid(),
    sku             VARCHAR(255)  not null unique,
    name            VARCHAR(255)  not null,
    price           DECIMAL(9, 6) not null,
    characteristics JSONB         not null,
    images          BYTEA[],
    description     VARCHAR(255)  not null
);

CREATE TABLE inventory (
    id                 UUID primary key default gen_random_uuid(),
    product_id         UUID    not null unique,
    available_quantity INTEGER not null CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER not null CHECK (reserved_quantity >= 0)
);

CREATE TABLE reservations (
    id         UUID primary key         default gen_random_uuid(),
    order_id   UUID               not null,
    product_id UUID               not null,
    amount     INTEGER            not null,
    status     reservation_status not null,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (order_id, product_id)
);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY NOT NULL,
    event_type    order_event_type,
    order_id      UUID             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE  DEFAULT NOW(),
    payload       JSONB,
    status        event_status,
    published_at  TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER          NOT NULL DEFAULT 0
);