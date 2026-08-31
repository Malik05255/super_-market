PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS retailers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL UNIQUE,
    name_ar TEXT NOT NULL,
    name_en TEXT NOT NULL,
    base_url TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS products (
    barcode TEXT PRIMARY KEY,
    name_ar TEXT,
    name_en TEXT,
    brand TEXT,
    image_url TEXT,
    gs1_verified INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS price_observations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    barcode TEXT NOT NULL,
    retailer_id INTEGER NOT NULL,
    branch_key TEXT NOT NULL DEFAULT 'online',
    price REAL NOT NULL CHECK (price >= 0),
    currency TEXT NOT NULL DEFAULT 'SAR',
    source_url TEXT,
    observed_at TEXT NOT NULL DEFAULT (datetime('now')),
    source_hash TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (barcode) REFERENCES products(barcode) ON DELETE CASCADE,
    FOREIGN KEY (retailer_id) REFERENCES retailers(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS price_observations_barcode_time_idx
    ON price_observations (barcode, observed_at DESC);

CREATE INDEX IF NOT EXISTS price_observations_retailer_time_idx
    ON price_observations (retailer_id, observed_at DESC);

-- Hot read model. The 12-hour refresh job writes one compact JSON document per barcode.
-- The mobile lookup therefore needs only one indexed row read.
CREATE TABLE IF NOT EXISTS product_snapshots (
    barcode TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    fresh_until TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS product_snapshots_fresh_until_idx
    ON product_snapshots (fresh_until);

-- Raw-data view remains useful for diagnostics/rebuilding snapshots.
CREATE VIEW IF NOT EXISTS product_price_snapshot AS
SELECT
    p.barcode,
    p.name_ar,
    p.name_en,
    p.image_url,
    (
        SELECT po.price
        FROM price_observations po
        WHERE po.barcode = p.barcode
        ORDER BY po.observed_at DESC
        LIMIT 1
    ) AS current_price,
    COALESCE((
        SELECT po.currency
        FROM price_observations po
        WHERE po.barcode = p.barcode
        ORDER BY po.observed_at DESC
        LIMIT 1
    ), 'SAR') AS currency,
    (
        SELECT r.name_ar
        FROM price_observations po
        JOIN retailers r ON r.id = po.retailer_id
        WHERE po.barcode = p.barcode
        ORDER BY po.observed_at DESC
        LIMIT 1
    ) AS retailer,
    (
        SELECT po.observed_at
        FROM price_observations po
        WHERE po.barcode = p.barcode
        ORDER BY po.observed_at DESC
        LIMIT 1
    ) AS price_updated_at,
    (
        SELECT MIN(po.price)
        FROM price_observations po
        WHERE po.barcode = p.barcode
          AND po.observed_at >= datetime('now', '-30 days')
    ) AS min_30d,
    (
        SELECT MAX(po.price)
        FROM price_observations po
        WHERE po.barcode = p.barcode
          AND po.observed_at >= datetime('now', '-30 days')
    ) AS max_30d,
    (
        SELECT COUNT(DISTINCT po.retailer_id)
        FROM price_observations po
        WHERE po.barcode = p.barcode
          AND po.observed_at >= datetime('now', '-30 days')
    ) AS source_count,
    1.0 AS confidence
FROM products p;
