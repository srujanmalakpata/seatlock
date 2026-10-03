-- V1 is treated as released: changes go into a new migration instead of editing it.

-- uq_seat_position already creates a btree index on exactly these columns, so this one only
-- doubled the index writes when a seat map is inserted.
DROP INDEX ix_seat_event_position;

-- Prices are bounded by the API, and the service sums them with overflow detection; this CHECK
-- keeps a negative (overflowed) total out of the table even if a code path misses that.
ALTER TABLE booking ADD CONSTRAINT ck_booking_total CHECK (total_cents >= 0);
