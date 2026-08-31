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

-- Unknown scans are captured without blocking the user's lookup. Repeated scans
-- increase priority instead of creating duplicate rows. The refresh workflow can
-- pull the most requested pending GTINs and investigate them before broad discovery.
CREATE TABLE IF NOT EXISTS missing_barcodes (
    barcode TEXT PRIMARY KEY,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    scan_count INTEGER NOT NULL DEFAULT 1,
    name_ar TEXT,
    name_en TEXT,
    image_url TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','resolved','ignored')),
    last_attempt_at TEXT,
    resolved_at TEXT
);

CREATE INDEX IF NOT EXISTS missing_barcodes_priority_idx
    ON missing_barcodes (status, scan_count DESC, last_seen_at DESC);
