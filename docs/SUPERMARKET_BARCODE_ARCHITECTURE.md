# Supermarket Barcode Engine

## Goal
Build a free-first Saudi product identity engine.

## Flow

Barcode Scanner
-> Local Cache
-> Saudi Master Barcode Resolver
-> SFDA / Open Sources
-> Product Identity
-> Store Price Aggregation

## Rules

- No paid APIs as a core dependency.
- Exact barcode matching only.
- Store pricing is separated from product identity.
- Unknown products are resolved automatically and cached.

## Data Model

- barcode
- product_name
- brand
- category
- size
- source
- confidence

## Next Implementation Steps

1. Add barcode resolver service.
2. Add product identity database.
3. Add store adapters.
4. Add scanner integration tests.
