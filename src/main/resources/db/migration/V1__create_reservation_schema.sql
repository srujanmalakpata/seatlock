-- Events and their generated seat maps.
CREATE TABLE event (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    capacity    INT          NOT NULL CHECK (capacity > 0),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_event_starts_at ON event (starts_at, id);

-- A hold reserves seats for a limited time; confirming it creates a booking.
CREATE TABLE seat_hold (
    id            UUID PRIMARY KEY,
    event_id      UUID         NOT NULL REFERENCES event (id),
    customer_ref  VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL,
    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_hold_expiry CHECK (expires_at > created_at)
);

-- The expiry sweeper only ever looks at ACTIVE holds ordered by expiry.
CREATE INDEX ix_hold_active_expiry ON seat_hold (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE seat (
    id            UUID PRIMARY KEY,
    event_id      UUID         NOT NULL REFERENCES event (id),
    section       VARCHAR(40)  NOT NULL,
    row_index     INT          NOT NULL,
    row_label     VARCHAR(8)   NOT NULL,
    seat_number   INT          NOT NULL,
    price_cents   BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    -- The hold that currently claims this seat (HELD) or that was confirmed into a booking (BOOKED).
    hold_id       UUID         NULL REFERENCES seat_hold (id),
    -- Optimistic-locking version: every state change is `UPDATE ... WHERE id = ? AND version = ?`.
    version       BIGINT       NOT NULL,
    CONSTRAINT uq_seat_position UNIQUE (event_id, section, row_index, seat_number),
    CONSTRAINT ck_seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    CONSTRAINT ck_seat_claim CHECK ((status = 'AVAILABLE') = (hold_id IS NULL)),
    CONSTRAINT ck_seat_price CHECK (price_cents >= 0)
);

CREATE INDEX ix_seat_event_position ON seat (event_id, section, row_index, seat_number);
CREATE INDEX ix_seat_hold ON seat (hold_id);

-- Permanent record of which seats a hold covered (seat.hold_id is cleared when a hold ends).
CREATE TABLE hold_seat (
    hold_id  UUID NOT NULL REFERENCES seat_hold (id),
    seat_id  UUID NOT NULL REFERENCES seat (id),
    PRIMARY KEY (hold_id, seat_id)
);

CREATE TABLE booking (
    id            UUID PRIMARY KEY,
    -- UNIQUE: a hold can be confirmed at most once, even if two confirms race.
    hold_id       UUID         NOT NULL UNIQUE REFERENCES seat_hold (id),
    event_id      UUID         NOT NULL REFERENCES event (id),
    customer_ref  VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    total_cents   BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    cancelled_at  TIMESTAMPTZ  NULL,
    version       BIGINT       NOT NULL,
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_booking_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- Idempotency-Key ledger: one row per client key; the stored response is replayed on retry.
CREATE TABLE idempotency_record (
    idempotency_key        VARCHAR(255) PRIMARY KEY,
    request_fingerprint    CHAR(64)     NOT NULL,
    state                  VARCHAR(16)  NOT NULL,
    response_status        INT          NULL,
    response_content_type  VARCHAR(255) NULL,
    response_location      VARCHAR(1024) NULL,
    response_body          TEXT         NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    completed_at           TIMESTAMPTZ  NULL,
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_completed CHECK (
        (state = 'COMPLETED') = (response_status IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_idempotency_created ON idempotency_record (created_at);
