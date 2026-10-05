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
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku             VARCHAR(255)  NOT NULL UNIQUE,
    name            VARCHAR(255)  NOT NULL,
    price           DECIMAL(12, 3) NOT NULL,
    characteristics JSONB         NOT NULL,
    image_url        VARCHAR(255),
    description     VARCHAR(255)  NOT NULL
);

CREATE TABLE inventory (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id         UUID    NOT NULL UNIQUE,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER NOT NULL CHECK (reserved_quantity >= 0)
);

CREATE TABLE reservations (
    id         UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    order_id   UUID               NOT NULL,
    product_id UUID               NOT NULL,
    amount     INTEGER            NOT NULL,
    status     reservation_status NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    released_at TIMESTAMP WITH TIME ZONE,
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