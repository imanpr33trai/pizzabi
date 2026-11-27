# Phase 8 W9 follow-up — Real migration CLI evidence (fresh + Flyway V1–V3)

**Date (UTC):** 2026-11-27 13:24–13:25\
**Repository:** `/workspace/pizzabi`, branch `ui-ux-changes`, working-tree HEAD `bb63746`\
**Scope:** evidence only — no source/docs edits, no commits, no deployment, no production DB.\
**Contract:** `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` §4.5; CLI flags from `rust/crates/pizzora-api/src/main.rs` (`--preflight-only`, `--migrate-only`, optional `--require-zero-workers`, `--require-zero-dead-letters`).

## Binary

Built from the current working tree:

```
source ~/.cargo/env && cargo build --manifest-path rust/Cargo.toml -p pizzora-api
# Finished `dev` profile in 12.74s
```

Binary: `/workspace/pizzabi/rust/target/debug/pizzora-api`\
SHA-256: `3c29890522483fd00d4f0a7d1f837f5fd234dbd2d6b4c94609034473fbd4ff38`\
Build log: `p8cli_build.log`

Only `pizzora-api` was needed: it implements both `--preflight-only` and `--migrate-only`. The worker binary only implements `--migrate-only` (`rust/crates/pizzora-workers/src/main.rs:23`) and was not required.

The API already listening on `0.0.0.0:8080` (PID 140384) was **not** touched: both flags return before the HTTP listener is created. The `pizzora` QA database was not touched. Only new local databases `pizzora_p8cli_fresh` and `pizzora_p8cli_adopt` in container `pizzora-pg18` were used.

## Exact invocations and exit codes

All commands were real CLI process invocations of the built binary; every log contains the exact command, UTC start/end timestamps, complete combined stdout/stderr, and exit code.

| # | Profile | DB state at invocation | Flag | UTC start | Exit | Log |
|---|---------|------------------------|------|-----------|------|-----|
| 1 | Fresh | empty `pizzora_p8cli_fresh` (0 base tables) | `--migrate-only` | 13:24:39 | **0** | `p8cli_fresh_migrate_only.log` |
| 2 | Fresh | same DB after bootstrap | `--preflight-only` | 13:24:40 | **0** | `p8cli_fresh_preflight_only.log` |
| 3 | Flyway V1–V3 adoption | pinned history/checksums, pre-handoff (46 domain tables) | `--preflight-only` | 13:24:41 | **0** | `p8cli_adopt_preflight_only.log` |
| 4 | Flyway V1–V3 adoption | same DB after handoff | `--migrate-only` | 13:24:41 | **0** | `p8cli_adopt_migrate_only.log` |

Exact commands (local non-production credential values redacted from committed evidence):

```
env DATABASE_URL='postgres://pizzora:<redacted-local-db-password>@127.0.0.1:5432/pizzora_p8cli_fresh' /workspace/pizzabi/rust/target/debug/pizzora-api --migrate-only
env DATABASE_URL='postgres://pizzora:<redacted-local-db-password>@127.0.0.1:5432/pizzora_p8cli_fresh' /workspace/pizzabi/rust/target/debug/pizzora-api --preflight-only
env DATABASE_URL='postgres://pizzora:<redacted-local-db-password>@127.0.0.1:5432/pizzora_p8cli_adopt' /workspace/pizzabi/rust/target/debug/pizzora-api --preflight-only
env DATABASE_URL='postgres://pizzora:<redacted-local-db-password>@127.0.0.1:5432/pizzora_p8cli_adopt' /workspace/pizzabi/rust/target/debug/pizzora-api --migrate-only
```

Key output evidence:

- Fresh `--migrate-only`: `mode=FreshBootstrap`, `applied_or_verified_versions=[1..11]`, "Pre-deploy migration step completed".
- Fresh `--preflight-only`: `sqlx_verified: true`, `catalog_invariants_ok: true`, `sqlx_versions: [1..11]`, zero dead letters, zero active worker sessions.
- Adoption `--preflight-only` (before handoff): `flyway_table_exists: true`, `flyway_verified: true`, `flyway_versions: ["1","2","3"]`, `sqlx_table_exists: false`, `catalog_invariants_ok: true`.
- Adoption `--migrate-only`: `mode=AdoptedExistingFlyway`, `applied_or_verified_versions=[1..11]`.

### Supplementary invocation (fresh, empty DB, before bootstrap)

```
env DATABASE_URL='postgres://pizzora:<redacted-local-db-password>@127.0.0.1:5432/pizzora_p8cli_fresh' /workspace/pizzabi/rust/target/debug/pizzora-api --preflight-only
# exit 1 — log: p8cli_fresh_preflight_empty.log
```

Output: `pizzora-api fatal error: UNSAFE_DATABASE_STATE: neither flyway_schema_history nor _sqlx_migrations exists; database is not ready for cutover`. This is the intended read-only cutover guard (`migrator.rs:414-419`), not a migration failure; the fresh profile's successful preflight is therefore recorded after bootstrap on the same fresh-created DB. This ordering is explicitly noted so the evidence is not misread as a skipped invocation.

## Flyway V1–V3 adoption profile recreation

Mirrors `rust/crates/pizzora-db/src/migrator.rs` test `pg18_locked_handoff_fresh_adopt_tamper_and_concurrency` (test code read only, not modified):

1. Applied the repository's V1–V3 SQL files byte-for-byte via `docker exec -i pizzora-pg18 psql -U pizzora -d pizzora_p8cli_adopt -v ON_ERROR_STOP=1 -f -`:
   `0001_core_schema.sql`, `0002_seed_rbac_and_settings.sql`, `0003_hot_path_indexes.sql`.
2. Created `flyway_schema_history` with the test's column shape and inserted the schema-creation row plus V1–V3 rows:
   - `V1__core_schema.sql` → checksum `-1549990012`
   - `V2__seed_rbac_and_settings.sql` → checksum `-1701246598`
   - `V3__hot_path_indexes.sql` → checksum `-1177665950`

Checksums were independently recomputed from the file bytes with the Flyway 11.3.4 line-wise CRC-32 algorithm and match `tools/golden-vectors/flyway_11_3_4_checksums.json` and the golden vectors in `migrator.rs`. Setup log: `p8cli_adopt_setup.log` (baseline: 46 domain tables / 47 raw tables including `flyway_schema_history`; four successful history rows).

## Table counts

| Profile | Before | After `--migrate-only` | `_sqlx_migrations` versions | Five new Phase 8 tables |
|---------|--------|------------------------|-----------------------------|-------------------------|
| Fresh | 0 | **51 domain tables** | 1–11 all `success = t` | all 5 present |
| Flyway adoption | 46 domain tables | **51 domain tables** | 1–11 all `success = t` | all 5 present |

Verification logs: `p8cli_fresh_verify.log`, `p8cli_adopt_verify.log`; database creation/initial counts: `p8cli_setup.log`.

Counting clarification: the CLI creates `_sqlx_migrations` and the adoption profile also retains `flyway_schema_history`. A raw `information_schema.tables` count is therefore 52 (fresh) / 53 (adoption), while the contract's 51 is the application-table inventory used by `EXPECTED_TABLES` / `verify_catalog_invariants`. Excluding those two bookkeeping tables gives exactly **51** on both profiles, and the CLI's own invariant check passed on both. This matches the existing `FRESH_SCHEMA_REPORT.md`, whose `pizzora_p8_check` database has no bookkeeping tables and thus a raw count of 51.

## Defect / findings

- **No functional defect found.** All four required CLI exit codes are 0 and both profiles end at the 51-table invariant with V1–V11 verified.
- **Evidence gap closed:** committed logs now show actual `--preflight-only` and `--migrate-only` CLI invocations on both profiles.
- **Interpretation note (not a defect):** `--preflight-only` on a completely empty database is intentionally rejected with `UNSAFE_DATABASE_STATE`; if a reviewer requires that flag to succeed *before* any migration on a fresh DB, that would be a contract/source change and is outside this evidence-only scope.

## Files written (all new, under `qa-artifacts/phase8/migrations/`)

- `PHASE8_MIGRATION_CLI_EVIDENCE.md` (this report)
- `p8cli_build.log`
- `p8cli_setup.log`
- `p8cli_fresh_preflight_empty.log`
- `p8cli_fresh_migrate_only.log`
- `p8cli_fresh_preflight_only.log`
- `p8cli_adopt_setup.log`
- `p8cli_adopt_preflight_only.log`
- `p8cli_adopt_migrate_only.log`
- `p8cli_fresh_verify.log`
- `p8cli_adopt_verify.log`

The temporary databases `pizzora_p8cli_fresh` and `pizzora_p8cli_adopt` remain in `pizzora-pg18` for reproducibility; no other databases were created, altered, or dropped.
