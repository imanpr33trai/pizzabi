# Phase 8 Checkpoint A — Fresh Schema Migration Evidence

**Date:** 2026-11-27
**Branch:** `ui-ux-changes`
**PostgreSQL:** 18.7 (container `pizzora-pg18`)

## Applied migrations

All 11 SQLx migration files applied in version order to an empty database
`pizzora_p8_check`:

| Version | File | Purpose |
|---------|------|---------|
| V1 | `0001_core_schema.sql` | 46 core tables (immutable) |
| V2 | `0002_seed_rbac_and_settings.sql` | RBAC + settings seeds (immutable) |
| V3 | `0003_hot_path_indexes.sql` | 13 hot-path indexes (immutable) |
| V4 | `0004_product_toppings.sql` | Product × topping availability + cross-product seed |
| V5 | `0005_cheeses.sql` | Per-product cheese options |
| V6 | `0006_sauces.sql` | Per-product sauce options |
| V7 | `0007_standard_toppings.sql` | Per-product standard toppings with `is_default` |
| V8 | `0008_half_and_half.sql` | Per-product half-and-half configs (JSONB) |
| V9 | `0009_cart_modifier_columns.sql` | 6 additive columns on `cart_items` |
| V10 | `0010_order_modifier_columns.sql` | 11 additive columns on `order_items` (no FKs) |
| V11 | `0011_modifier_indexes.sql` | 12 new lookup indexes |

## Table count

```sql
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
-- 51
```

Expected 51 = 46 existing + 5 new (`product_toppings`, `cheeses`, `sauces`,
`standard_toppings`, `half_and_half_configs`). **PASS.**

## Tests

| Command | Result |
|---------|--------|
| `cargo test -p pizzora-db --lib` | 7 passed, 2 ignored |
| `TEST_POSTGRES_URL=... cargo test -p pizzora-db --lib -- --ignored --test-threads=1` | 3 passed, 0 failed |
| `cargo build --workspace` | PASS |

Live integration tests cover:
1. Fresh bootstrap + idempotent resume (11 versions applied).
2. Flyway V1–V3 adoption → SQLx V4–V11 handoff (baseline 46-table verification before
   migrations; full 51-table verification after).
3. Tampered checksum, rogue Flyway row, and missing-index rejection with advisory lock release.
4. Rollback/reapply: drop V4+ objects and migration rows, re-run handoff, verify 51 tables,
   idempotent second reapply.

Logs: `db_unit_tests.log`, `pg18_integration_tests.log`.

**Verdict: Checkpoint A — VERIFIED.**
