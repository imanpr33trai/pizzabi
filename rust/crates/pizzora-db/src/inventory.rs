use std::collections::{BTreeMap, HashMap};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::audit::{record_audit_log, AuditContext};
use crate::error::{new_uuid_v4, DbError};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum IngredientUnit {
    Grams,
    Ml,
    Units,
}

impl IngredientUnit {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Grams => "GRAMS",
            Self::Ml => "ML",
            Self::Units => "UNITS",
        }
    }

    pub fn from_db_str(raw: &str) -> Result<Self, DbError> {
        match raw {
            "GRAMS" => Ok(Self::Grams),
            "ML" => Ok(Self::Ml),
            "UNITS" => Ok(Self::Units),
            other => Err(DbError::CatalogInvariantViolation(format!(
                "Unsupported ingredient unit '{other}'"
            ))),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum InventoryLedgerReason {
    Restock,
    Reserve,
    Commit,
    Release,
    ExpireRelease,
    CancelRestore,
    ManualAdjust,
}

impl InventoryLedgerReason {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Restock => "RESTOCK",
            Self::Reserve => "RESERVE",
            Self::Commit => "COMMIT",
            Self::Release => "RELEASE",
            Self::ExpireRelease => "EXPIRE_RELEASE",
            Self::CancelRestore => "CANCEL_RESTORE",
            Self::ManualAdjust => "MANUAL_ADJUST",
        }
    }

    pub fn from_db_str(raw: &str) -> Result<Self, DbError> {
        match raw {
            "RESTOCK" => Ok(Self::Restock),
            "RESERVE" => Ok(Self::Reserve),
            "COMMIT" => Ok(Self::Commit),
            "RELEASE" => Ok(Self::Release),
            "EXPIRE_RELEASE" => Ok(Self::ExpireRelease),
            "CANCEL_RESTORE" => Ok(Self::CancelRestore),
            "MANUAL_ADJUST" => Ok(Self::ManualAdjust),
            other => Err(DbError::CatalogInvariantViolation(format!(
                "Unsupported inventory_ledger reason '{other}'"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InventoryItemDto {
    pub ingredient_id: String,
    pub name: String,
    pub unit: IngredientUnit,
    pub low_stock_threshold: i64,
    pub is_active: bool,
    pub on_hand_qty: i64,
    pub reserved_qty: i64,
    pub available_qty: i64,
    pub is_low_stock: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InventoryLedgerEntryDto {
    pub id: String,
    pub ingredient_id: String,
    pub ingredient_name: String,
    pub unit: IngredientUnit,
    #[serde(default)]
    pub order_id: Option<String>,
    pub operation_id: String,
    pub delta_on_hand: i64,
    pub delta_reserved: i64,
    pub reason: InventoryLedgerReason,
    #[serde(default)]
    pub actor_user_id: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateIngredientRequest {
    pub name: String,
    pub unit: IngredientUnit,
    pub low_stock_threshold: i64,
    pub initial_stock_qty: i64,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateIngredientRequest {
    pub name: Option<String>,
    pub unit: Option<IngredientUnit>,
    pub low_stock_threshold: Option<i64>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdjustInventoryRequest {
    pub ingredient_id: String,
    pub operation_id: String,
    pub delta_qty: i64,
    pub reason: InventoryLedgerReason,
}

fn default_true() -> bool {
    true
}

#[derive(Clone)]
pub struct InventoryService {
    pool: PgPool,
}

impl InventoryService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn list_inventory(&self) -> Result<Vec<InventoryItemDto>, DbError> {
        let rows = sqlx::query(
            r#"
            SELECT i.id AS ingredient_id, i.name, i.unit, i.low_stock_threshold, i.is_active,
                   inv.on_hand_qty, inv.reserved_qty, inv.available_qty, inv.updated_at
            FROM ingredients i
            JOIN inventory inv ON inv.ingredient_id = i.id
            ORDER BY i.name ASC
            "#,
        )
        .fetch_all(&self.pool)
        .await?;

        rows.iter().map(row_to_inventory_item_dto).collect()
    }

    pub async fn create_ingredient(
        &self,
        request: &CreateIngredientRequest,
        audit: &AuditContext,
    ) -> Result<InventoryItemDto, DbError> {
        let normalized_name = request.name.trim();
        if normalized_name.is_empty() || normalized_name.len() > 100 {
            return Err(DbError::Validation {
                code: "INVALID_INGREDIENT_NAME",
                message: "Ingredient name must be 1..100 characters.".to_owned(),
            });
        }
        if request.low_stock_threshold < 0 {
            return Err(DbError::Validation {
                code: "INVALID_THRESHOLD",
                message: "low_stock_threshold must be non-negative.".to_owned(),
            });
        }
        if request.initial_stock_qty < 0 {
            return Err(DbError::Validation {
                code: "INVALID_INITIAL_STOCK",
                message: "initial_stock_qty must be non-negative.".to_owned(),
            });
        }

        let ingredient_id = new_uuid_v4();
        let mut tx = self.pool.begin().await?;
        sqlx::query(
            r#"
            INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active)
            VALUES ($1, $2, $3, $4, $5)
            "#,
        )
        .bind(ingredient_id)
        .bind(normalized_name)
        .bind(request.unit.as_str())
        .bind(request.low_stock_threshold)
        .bind(request.is_active)
        .execute(&mut *tx)
        .await
        .map_err(|err| {
            if DbError::is_unique_violation(&err) {
                DbError::Conflict {
                    code: "INGREDIENT_EXISTS",
                    message: format!("Ingredient '{normalized_name}' already exists."),
                }
            } else {
                DbError::Sqlx(err)
            }
        })?;

        sqlx::query(
            "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES ($1, $2, 0)",
        )
        .bind(ingredient_id)
        .bind(request.initial_stock_qty)
        .execute(&mut *tx)
        .await?;

        if request.initial_stock_qty > 0 {
            let op_id = format!("initial:{ingredient_id}");
            insert_ledger_idempotent(
                &mut tx,
                ingredient_id,
                None,
                &op_id,
                request.initial_stock_qty,
                0,
                InventoryLedgerReason::Restock,
                audit.actor_user_id,
            )
            .await?;
        }

        let item = load_single_inventory_item(&mut tx, ingredient_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "INGREDIENT_NOT_FOUND",
                message: format!("Ingredient {ingredient_id} not found."),
            })?;

        let after_val = serde_json::to_value(&item).ok();
        let entity_id = ingredient_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "INGREDIENT_CREATED",
            "ingredient",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(item)
    }

    pub async fn adjust_inventory(
        &self,
        request: &AdjustInventoryRequest,
        audit: &AuditContext,
    ) -> Result<InventoryItemDto, DbError> {
        let ingredient_id = Uuid::parse_str(request.ingredient_id.trim()).map_err(|_| {
            DbError::Validation {
                code: "INVALID_UUID",
                message: format!("Invalid ingredient_id UUID: {}", request.ingredient_id),
            }
        })?;
        let operation_id = request.operation_id.trim();
        if operation_id.is_empty() || operation_id.len() > 128 {
            return Err(DbError::Validation {
                code: "INVALID_OPERATION_ID",
                message: "operation_id must be 1..128 characters.".to_owned(),
            });
        }
        if !matches!(
            request.reason,
            InventoryLedgerReason::Restock | InventoryLedgerReason::ManualAdjust
        ) {
            return Err(DbError::Validation {
                code: "INVALID_ADJUST_REASON",
                message: "reason must be RESTOCK or MANUAL_ADJUST.".to_owned(),
            });
        }
        if request.reason == InventoryLedgerReason::Restock && request.delta_qty <= 0 {
            return Err(DbError::Validation {
                code: "INVALID_DELTA_QTY",
                message: "RESTOCK delta_qty must be positive.".to_owned(),
            });
        }
        if request.delta_qty == 0 {
            return Err(DbError::Validation {
                code: "INVALID_DELTA_QTY",
                message: "delta_qty must be non-zero.".to_owned(),
            });
        }

        let mut tx = self.pool.begin().await?;
        let locked = sqlx::query(
            r#"
            SELECT i.id, i.name, inv.on_hand_qty, inv.reserved_qty
            FROM ingredients i
            JOIN inventory inv ON inv.ingredient_id = i.id
            WHERE i.id = $1
            FOR UPDATE OF inv
            "#,
        )
        .bind(ingredient_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| DbError::NotFound {
            code: "INGREDIENT_NOT_FOUND",
            message: format!("Ingredient {ingredient_id} not found."),
        })?;

        let name: String = locked.try_get("name")?;
        let on_hand_qty: i64 = locked.try_get("on_hand_qty")?;
        let reserved_qty: i64 = locked.try_get("reserved_qty")?;

        let inserted = insert_ledger_idempotent(
            &mut tx,
            ingredient_id,
            None,
            operation_id,
            request.delta_qty,
            0,
            request.reason,
            audit.actor_user_id,
        )
        .await?;

        if !inserted {
            if let Some(existing) = load_ledger_by_operation_id(&mut tx, operation_id).await? {
                if existing.ingredient_id != ingredient_id.to_string()
                    || existing.delta_on_hand != request.delta_qty
                    || existing.reason != request.reason
                {
                    return Err(DbError::Conflict {
                        code: "OPERATION_ID_CONFLICT",
                        message: format!(
                            "operation_id '{operation_id}' was already used for a different inventory operation."
                        ),
                    });
                }
            }
            let item = load_single_inventory_item(&mut tx, ingredient_id)
                .await?
                .ok_or_else(|| DbError::NotFound {
                    code: "INGREDIENT_NOT_FOUND",
                    message: format!("Ingredient {ingredient_id} not found."),
                })?;
            tx.commit().await?;
            return Ok(item);
        }

        let new_on_hand = on_hand_qty
            .checked_add(request.delta_qty)
            .ok_or_else(|| DbError::Validation {
                code: "OVERFLOW",
                message: "Inventory quantity overflow.".to_owned(),
            })?;
        if new_on_hand < 0 || new_on_hand < reserved_qty {
            return Err(DbError::Unprocessable {
                code: "INSUFFICIENT_AVAILABLE_STOCK",
                message: format!(
                    "Cannot set {name} on_hand_qty ({new_on_hand}) below reserved_qty ({reserved_qty})."
                ),
            });
        }

        sqlx::query(
            "UPDATE inventory SET on_hand_qty = $1, updated_at = NOW() WHERE ingredient_id = $2",
        )
        .bind(new_on_hand)
        .bind(ingredient_id)
        .execute(&mut *tx)
        .await?;

        let updated = load_single_inventory_item(&mut tx, ingredient_id)
            .await?
            .ok_or_else(|| DbError::NotFound {
                code: "INGREDIENT_NOT_FOUND",
                message: format!("Ingredient {ingredient_id} not found."),
            })?;

        let before_val = serde_json::json!({
            "on_hand_qty": on_hand_qty,
            "reserved_qty": reserved_qty
        });
        let after_val = serde_json::json!({
            "on_hand_qty": updated.on_hand_qty,
            "reserved_qty": updated.reserved_qty,
            "operation_id": operation_id
        });
        let entity_id = ingredient_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "INVENTORY_ADJUSTED",
            "inventory",
            &entity_id,
            Some(&before_val),
            Some(&after_val),
        )
        .await?;

        tx.commit().await?;
        Ok(updated)
    }

    pub async fn list_ledger(
        &self,
        ingredient_id: Option<Uuid>,
        limit: i64,
    ) -> Result<Vec<InventoryLedgerEntryDto>, DbError> {
        let clamped_limit = limit.clamp(1, 200);
        let rows = match ingredient_id {
            Some(id) => {
                sqlx::query(
                    r#"
                    SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
                           l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
                           l.reason, l.actor_user_id, l.created_at
                    FROM inventory_ledger l
                    JOIN ingredients i ON i.id = l.ingredient_id
                    WHERE l.ingredient_id = $1
                    ORDER BY l.created_at DESC, l.id DESC
                    LIMIT $2
                    "#,
                )
                .bind(id)
                .bind(clamped_limit)
                .fetch_all(&self.pool)
                .await?
            }
            None => {
                sqlx::query(
                    r#"
                    SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
                           l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
                           l.reason, l.actor_user_id, l.created_at
                    FROM inventory_ledger l
                    JOIN ingredients i ON i.id = l.ingredient_id
                    ORDER BY l.created_at DESC, l.id DESC
                    LIMIT $1
                    "#,
                )
                .bind(clamped_limit)
                .fetch_all(&self.pool)
                .await?
            }
        };

        rows.iter().map(row_to_ledger_dto).collect()
    }

    /// Atomically reserves or immediately commits (for COD) ingredient quantities for `order_id`.
    /// Locks inventory rows strictly in ascending ingredient UUID string order to eliminate deadlocks.
    pub async fn reserve_for_order(
        conn: &mut PgConnection,
        order_id: Uuid,
        required_by_ingredient: &HashMap<Uuid, i64>,
        expires_at: Option<DateTime<Utc>>,
        immediate_commit: bool,
        actor_user_id: Option<Uuid>,
    ) -> Result<(), DbError> {
        let mut sorted_reqs: BTreeMap<String, (Uuid, i64)> = BTreeMap::new();
        for (&ingredient_id, &qty) in required_by_ingredient {
            if qty > 0 {
                sorted_reqs.insert(ingredient_id.to_string(), (ingredient_id, qty));
            }
        }
        if sorted_reqs.is_empty() {
            return Ok(());
        }

        let sorted_ids: Vec<Uuid> = sorted_reqs.values().map(|(id, _)| *id).collect();
        let locked_map = lock_inventory_rows_ordered(&mut *conn, &sorted_ids).await?;

        for (ingredient_id, required_qty) in sorted_reqs.values() {
            let stock = locked_map.get(ingredient_id).ok_or_else(|| DbError::Unprocessable {
                code: "INGREDIENT_NOT_FOUND",
                message: format!("Ingredient {ingredient_id} is missing from inventory."),
            })?;
            if !stock.is_active {
                return Err(DbError::Unprocessable {
                    code: "INGREDIENT_UNAVAILABLE",
                    message: format!("Ingredient '{}' is currently inactive.", stock.name),
                });
            }
            let available_qty = stock.on_hand_qty - stock.reserved_qty;
            if available_qty < *required_qty {
                return Err(DbError::Unprocessable {
                    code: "INSUFFICIENT_INVENTORY",
                    message: format!(
                        "Insufficient stock for '{}'. Required {required_qty}, available {available_qty}.",
                        stock.name
                    ),
                });
            }
        }

        let reason = if immediate_commit {
            InventoryLedgerReason::Commit
        } else {
            InventoryLedgerReason::Reserve
        };
        let reservation_status = if immediate_commit {
            "COMMITTED"
        } else {
            "ACTIVE"
        };
        let op_prefix = if immediate_commit { "commit" } else { "reserve" };

        for (ingredient_id, required_qty) in sorted_reqs.into_values() {
            let operation_id = format!("{op_prefix}:{order_id}:{ingredient_id}");
            let (delta_on_hand, delta_reserved) = if immediate_commit {
                (-required_qty, 0)
            } else {
                (0, required_qty)
            };

            let inserted = insert_ledger_idempotent(
                &mut *conn,
                ingredient_id,
                Some(order_id),
                &operation_id,
                delta_on_hand,
                delta_reserved,
                reason,
                actor_user_id,
            )
            .await?;

            if inserted {
                if immediate_commit {
                    sqlx::query(
                        "UPDATE inventory SET on_hand_qty = on_hand_qty - $1, updated_at = NOW() WHERE ingredient_id = $2",
                    )
                    .bind(required_qty)
                    .bind(ingredient_id)
                    .execute(&mut *conn)
                    .await?;
                } else {
                    sqlx::query(
                        "UPDATE inventory SET reserved_qty = reserved_qty + $1, updated_at = NOW() WHERE ingredient_id = $2",
                    )
                    .bind(required_qty)
                    .bind(ingredient_id)
                    .execute(&mut *conn)
                    .await?;
                }

                let eff_expires = if immediate_commit { None } else { expires_at };
                sqlx::query(
                    r#"
                    INSERT INTO inventory_reservations (id, order_id, ingredient_id, quantity, status, expires_at)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    ON CONFLICT (order_id, ingredient_id) DO NOTHING
                    "#,
                )
                .bind(new_uuid_v4())
                .bind(order_id)
                .bind(ingredient_id)
                .bind(required_qty)
                .bind(reservation_status)
                .bind(eff_expires)
                .execute(&mut *conn)
                .await?;
            }
        }

        Ok(())
    }

    pub async fn commit_reserved_inventory(
        conn: &mut PgConnection,
        order_id: Uuid,
        actor_user_id: Option<Uuid>,
    ) -> Result<(), DbError> {
        let active = load_order_reservations_for_update(&mut *conn, order_id, "ACTIVE").await?;
        if active.is_empty() {
            return Ok(());
        }

        let ids: Vec<Uuid> = active.iter().map(|r| r.ingredient_id).collect();
        lock_inventory_rows_ordered(&mut *conn, &ids).await?;

        for row in active {
            let res = sqlx::query(
                r#"
                UPDATE inventory_reservations
                SET status = 'COMMITTED', expires_at = NULL, updated_at = NOW()
                WHERE order_id = $1 AND ingredient_id = $2 AND status = 'ACTIVE'
                "#,
            )
            .bind(order_id)
            .bind(row.ingredient_id)
            .execute(&mut *conn)
            .await?;
            if res.rows_affected() == 0 {
                continue;
            }

            let op_id = format!("commit:{order_id}:{}", row.ingredient_id);
            let inserted = insert_ledger_idempotent(
                &mut *conn,
                row.ingredient_id,
                Some(order_id),
                &op_id,
                -row.quantity,
                -row.quantity,
                InventoryLedgerReason::Commit,
                actor_user_id,
            )
            .await?;

            if inserted {
                sqlx::query(
                    r#"
                    UPDATE inventory
                    SET on_hand_qty = on_hand_qty - $1,
                        reserved_qty = reserved_qty - $1,
                        updated_at = NOW()
                    WHERE ingredient_id = $2
                    "#,
                )
                .bind(row.quantity)
                .bind(row.ingredient_id)
                .execute(&mut *conn)
                .await?;
            }
        }

        Ok(())
    }

    pub async fn release_reserved_inventory(
        conn: &mut PgConnection,
        order_id: Uuid,
        expired: bool,
        actor_user_id: Option<Uuid>,
    ) -> Result<(), DbError> {
        let active = load_order_reservations_for_update(&mut *conn, order_id, "ACTIVE").await?;
        if active.is_empty() {
            return Ok(());
        }

        let ids: Vec<Uuid> = active.iter().map(|r| r.ingredient_id).collect();
        lock_inventory_rows_ordered(&mut *conn, &ids).await?;

        let target_status = if expired { "EXPIRED" } else { "RELEASED" };
        let reason = if expired {
            InventoryLedgerReason::ExpireRelease
        } else {
            InventoryLedgerReason::Release
        };
        let prefix = if expired { "expire_release" } else { "release" };

        for row in active {
            let res = sqlx::query(
                r#"
                UPDATE inventory_reservations
                SET status = $1, expires_at = NULL, updated_at = NOW()
                WHERE order_id = $2 AND ingredient_id = $3 AND status = 'ACTIVE'
                "#,
            )
            .bind(target_status)
            .bind(order_id)
            .bind(row.ingredient_id)
            .execute(&mut *conn)
            .await?;
            if res.rows_affected() == 0 {
                continue;
            }

            let op_id = format!("{prefix}:{order_id}:{}", row.ingredient_id);
            let inserted = insert_ledger_idempotent(
                &mut *conn,
                row.ingredient_id,
                Some(order_id),
                &op_id,
                0,
                -row.quantity,
                reason,
                actor_user_id,
            )
            .await?;

            if inserted {
                sqlx::query(
                    "UPDATE inventory SET reserved_qty = reserved_qty - $1, updated_at = NOW() WHERE ingredient_id = $2",
                )
                .bind(row.quantity)
                .bind(row.ingredient_id)
                .execute(&mut *conn)
                .await?;
            }
        }

        Ok(())
    }

    pub async fn restore_committed_inventory(
        conn: &mut PgConnection,
        order_id: Uuid,
        actor_user_id: Option<Uuid>,
    ) -> Result<(), DbError> {
        let committed =
            load_order_reservations_for_update(&mut *conn, order_id, "COMMITTED").await?;
        if committed.is_empty() {
            return Ok(());
        }

        let ids: Vec<Uuid> = committed.iter().map(|r| r.ingredient_id).collect();
        lock_inventory_rows_ordered(&mut *conn, &ids).await?;

        for row in committed {
            let res = sqlx::query(
                r#"
                UPDATE inventory_reservations
                SET status = 'RESTORED', updated_at = NOW()
                WHERE order_id = $1 AND ingredient_id = $2 AND status = 'COMMITTED'
                "#,
            )
            .bind(order_id)
            .bind(row.ingredient_id)
            .execute(&mut *conn)
            .await?;
            if res.rows_affected() == 0 {
                continue;
            }

            let op_id = format!("cancel_restore:{order_id}:{}", row.ingredient_id);
            let inserted = insert_ledger_idempotent(
                &mut *conn,
                row.ingredient_id,
                Some(order_id),
                &op_id,
                row.quantity,
                0,
                InventoryLedgerReason::CancelRestore,
                actor_user_id,
            )
            .await?;

            if inserted {
                sqlx::query(
                    "UPDATE inventory SET on_hand_qty = on_hand_qty + $1, updated_at = NOW() WHERE ingredient_id = $2",
                )
                .bind(row.quantity)
                .bind(row.ingredient_id)
                .execute(&mut *conn)
                .await?;
            }
        }

        Ok(())
    }
}

#[derive(Debug, Clone)]
struct LockedStockRow {
    name: String,
    on_hand_qty: i64,
    reserved_qty: i64,
    is_active: bool,
}

#[derive(Debug, Clone)]
struct OrderReservationRow {
    ingredient_id: Uuid,
    quantity: i64,
}

async fn lock_inventory_rows_ordered(
    conn: &mut PgConnection,
    sorted_ids: &[Uuid],
) -> Result<HashMap<Uuid, LockedStockRow>, DbError> {
    if sorted_ids.is_empty() {
        return Ok(HashMap::new());
    }
    let rows = sqlx::query(
        r#"
        SELECT inv.ingredient_id, i.name, i.is_active, inv.on_hand_qty, inv.reserved_qty
        FROM inventory inv
        JOIN ingredients i ON i.id = inv.ingredient_id
        WHERE inv.ingredient_id = ANY($1)
        ORDER BY inv.ingredient_id::text ASC
        FOR UPDATE OF inv
        "#,
    )
    .bind(sorted_ids)
    .fetch_all(&mut *conn)
    .await?;

    let mut map = HashMap::with_capacity(rows.len());
    for row in rows {
        let id: Uuid = row.try_get("ingredient_id")?;
        map.insert(
            id,
            LockedStockRow {
                name: row.try_get("name")?,
                on_hand_qty: row.try_get("on_hand_qty")?,
                reserved_qty: row.try_get("reserved_qty")?,
                is_active: row.try_get("is_active")?,
            },
        );
    }
    Ok(map)
}

async fn load_order_reservations_for_update(
    conn: &mut PgConnection,
    order_id: Uuid,
    status: &str,
) -> Result<Vec<OrderReservationRow>, DbError> {
    let rows = sqlx::query(
        r#"
        SELECT ingredient_id, quantity
        FROM inventory_reservations
        WHERE order_id = $1 AND status = $2
        ORDER BY ingredient_id::text ASC
        FOR UPDATE
        "#,
    )
    .bind(order_id)
    .bind(status)
    .fetch_all(&mut *conn)
    .await?;

    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        out.push(OrderReservationRow {
            ingredient_id: row.try_get("ingredient_id")?,
            quantity: row.try_get("quantity")?,
        });
    }
    Ok(out)
}

#[allow(clippy::too_many_arguments)]
async fn insert_ledger_idempotent(
    conn: &mut PgConnection,
    ingredient_id: Uuid,
    order_id: Option<Uuid>,
    operation_id: &str,
    delta_on_hand: i64,
    delta_reserved: i64,
    reason: InventoryLedgerReason,
    actor_user_id: Option<Uuid>,
) -> Result<bool, DbError> {
    let row = sqlx::query_scalar::<_, Uuid>(
        r#"
        INSERT INTO inventory_ledger (
            id, ingredient_id, order_id, operation_id, delta_on_hand, delta_reserved, reason, actor_user_id
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
        ON CONFLICT (operation_id) DO NOTHING
        RETURNING id
        "#,
    )
    .bind(new_uuid_v4())
    .bind(ingredient_id)
    .bind(order_id)
    .bind(operation_id)
    .bind(delta_on_hand)
    .bind(delta_reserved)
    .bind(reason.as_str())
    .bind(actor_user_id)
    .fetch_optional(&mut *conn)
    .await?;

    Ok(row.is_some())
}

async fn load_ledger_by_operation_id(
    conn: &mut PgConnection,
    operation_id: &str,
) -> Result<Option<InventoryLedgerEntryDto>, DbError> {
    let row = sqlx::query(
        r#"
        SELECT l.id, l.ingredient_id, i.name AS ingredient_name, i.unit,
               l.order_id, l.operation_id, l.delta_on_hand, l.delta_reserved,
               l.reason, l.actor_user_id, l.created_at
        FROM inventory_ledger l
        JOIN ingredients i ON i.id = l.ingredient_id
        WHERE l.operation_id = $1
        "#,
    )
    .bind(operation_id)
    .fetch_optional(&mut *conn)
    .await?;

    row.as_ref().map(row_to_ledger_dto).transpose()
}

async fn load_single_inventory_item(
    conn: &mut PgConnection,
    ingredient_id: Uuid,
) -> Result<Option<InventoryItemDto>, DbError> {
    let row = sqlx::query(
        r#"
        SELECT i.id AS ingredient_id, i.name, i.unit, i.low_stock_threshold, i.is_active,
               inv.on_hand_qty, inv.reserved_qty, inv.available_qty, inv.updated_at
        FROM ingredients i
        JOIN inventory inv ON inv.ingredient_id = i.id
        WHERE i.id = $1
        "#,
    )
    .bind(ingredient_id)
    .fetch_optional(&mut *conn)
    .await?;

    row.as_ref().map(row_to_inventory_item_dto).transpose()
}

fn row_to_inventory_item_dto(row: &sqlx::postgres::PgRow) -> Result<InventoryItemDto, DbError> {
    let ingredient_id: Uuid = row.try_get("ingredient_id")?;
    let unit_raw: String = row.try_get("unit")?;
    let available: i64 = row.try_get("available_qty")?;
    let threshold: i64 = row.try_get("low_stock_threshold")?;
    let updated_at: DateTime<Utc> = row.try_get("updated_at")?;
    Ok(InventoryItemDto {
        ingredient_id: ingredient_id.to_string(),
        name: row.try_get("name")?,
        unit: IngredientUnit::from_db_str(&unit_raw)?,
        low_stock_threshold: threshold,
        is_active: row.try_get("is_active")?,
        on_hand_qty: row.try_get("on_hand_qty")?,
        reserved_qty: row.try_get("reserved_qty")?,
        available_qty: available,
        is_low_stock: available <= threshold,
        updated_at: updated_at.to_rfc3339(),
    })
}

fn row_to_ledger_dto(row: &sqlx::postgres::PgRow) -> Result<InventoryLedgerEntryDto, DbError> {
    let id: Uuid = row.try_get("id")?;
    let ingredient_id: Uuid = row.try_get("ingredient_id")?;
    let order_id: Option<Uuid> = row.try_get("order_id")?;
    let actor_user_id: Option<Uuid> = row.try_get("actor_user_id")?;
    let unit_raw: String = row.try_get("unit")?;
    let reason_raw: String = row.try_get("reason")?;
    let created_at: DateTime<Utc> = row.try_get("created_at")?;

    Ok(InventoryLedgerEntryDto {
        id: id.to_string(),
        ingredient_id: ingredient_id.to_string(),
        ingredient_name: row.try_get("ingredient_name")?,
        unit: IngredientUnit::from_db_str(&unit_raw)?,
        order_id: order_id.map(|u| u.to_string()),
        operation_id: row.try_get("operation_id")?,
        delta_on_hand: row.try_get("delta_on_hand")?,
        delta_reserved: row.try_get("delta_reserved")?,
        reason: InventoryLedgerReason::from_db_str(&reason_raw)?,
        actor_user_id: actor_user_id.map(|u| u.to_string()),
        created_at: created_at.to_rfc3339(),
    })
}
