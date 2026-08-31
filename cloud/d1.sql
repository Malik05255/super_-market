PRAGMA foreign_keys = ON;

-- D1 is the fastest read replica. It stores only denormalized snapshots so the
-- app can resolve barcode -> complete response with one indexed row lookup.
CREATE TABLE IF NOT EXISTS product_snapshots (
    barcode TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS system_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
