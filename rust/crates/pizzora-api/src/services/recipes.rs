use std::collections::{BTreeMap, HashMap};

use pizzora_db::{new_uuid_v4, record_audit_log, AuditContext, IngredientUnit};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    PutRecipeRequest, RecipeBindingDto, RecipeIngredientLineDto, RecipeTargetType,
    RecipesOverviewResponse,
};
use crate::error::ApiError;
use crate::extractors::parse_uuid_param;

#[derive(Clone, Debug)]
pub struct CheckoutRecipeLineDemandInput {
    pub variant_id: Uuid,
    pub crust_id: Uuid,
    pub topping_ids: Vec<Uuid>,
    pub quantity: i32,
}

#[derive(Clone)]
pub struct RecipeService {
    pool: PgPool,
}

impl RecipeService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn list_recipes(&self) -> Result<RecipesOverviewResponse, ApiError> {
        let mut conn = self.pool.acquire().await?;

        let variant_rows = sqlx::query(
            r#"
            SELECT pv.id AS target_id,
                   (p.name || ' (' || pv.size_code || ')') AS target_label
            FROM product_variants pv
            JOIN products p ON p.id = pv.product_id
            ORDER BY p.name ASC, pv.size_code ASC
            "#,
        )
        .fetch_all(&mut *conn)
        .await?;

        let crust_rows = sqlx::query(
            "SELECT id AS target_id, name AS target_label FROM crust_options ORDER BY name ASC",
        )
        .fetch_all(&mut *conn)
        .await?;

        let topping_rows = sqlx::query(
            "SELECT id AS target_id, name AS target_label FROM toppings ORDER BY name ASC",
        )
        .fetch_all(&mut *conn)
        .await?;

        let variant_lines = load_recipe_lines(&mut conn, RecipeTargetType::Variant).await?;
        let crust_lines = load_recipe_lines(&mut conn, RecipeTargetType::Crust).await?;
        let topping_lines = load_recipe_lines(&mut conn, RecipeTargetType::Topping).await?;

        Ok(RecipesOverviewResponse {
            variants: build_bindings(variant_rows, &variant_lines, RecipeTargetType::Variant)?,
            crusts: build_bindings(crust_rows, &crust_lines, RecipeTargetType::Crust)?,
            toppings: build_bindings(topping_rows, &topping_lines, RecipeTargetType::Topping)?,
        })
    }

    pub async fn put_recipe(
        &self,
        request: &PutRecipeRequest,
        audit: &AuditContext,
    ) -> Result<RecipeBindingDto, ApiError> {
        let target_id = parse_uuid_param(&request.target_id, "target_id")?;

        let mut merged_qty: BTreeMap<Uuid, i32> = BTreeMap::new();
        for line in &request.ingredients {
            if line.quantity <= 0 {
                return Err(ApiError::validation(
                    "INVALID_RECIPE_QUANTITY",
                    "Recipe ingredient quantity must be greater than 0.",
                ));
            }
            let ingredient_id = parse_uuid_param(&line.ingredient_id, "ingredient_id")?;
            let entry = merged_qty.entry(ingredient_id).or_insert(0);
            *entry = entry.saturating_add(line.quantity);
        }

        let mut tx = self.pool.begin().await?;

        let target_label = match request.target_type {
            RecipeTargetType::Variant => sqlx::query_scalar::<_, String>(
                "SELECT (p.name || ' (' || pv.size_code || ')') FROM product_variants pv JOIN products p ON p.id = pv.product_id WHERE pv.id = $1",
            )
            .bind(target_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| ApiError::not_found("VARIANT_NOT_FOUND", format!("Variant {target_id} not found.")))?,
            RecipeTargetType::Crust => sqlx::query_scalar::<_, String>(
                "SELECT name FROM crust_options WHERE id = $1",
            )
            .bind(target_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| ApiError::not_found("CRUST_NOT_FOUND", format!("Crust {target_id} not found.")))?,
            RecipeTargetType::Topping => sqlx::query_scalar::<_, String>(
                "SELECT name FROM toppings WHERE id = $1",
            )
            .bind(target_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| ApiError::not_found("TOPPING_NOT_FOUND", format!("Topping {target_id} not found.")))?,
        };

        for ingredient_id in merged_qty.keys() {
            let exists: Option<i32> = sqlx::query_scalar("SELECT 1 FROM ingredients WHERE id = $1")
                .bind(*ingredient_id)
                .fetch_optional(&mut *tx)
                .await?;
            if exists.is_none() {
                return Err(ApiError::validation(
                    "INGREDIENT_NOT_FOUND",
                    format!("Ingredient {ingredient_id} does not exist."),
                ));
            }
        }

        match request.target_type {
            RecipeTargetType::Variant => {
                sqlx::query("DELETE FROM variant_recipes WHERE variant_id = $1")
                    .bind(target_id)
                    .execute(&mut *tx)
                    .await?;
                for (ingredient_id, qty) in &merged_qty {
                    sqlx::query(
                        "INSERT INTO variant_recipes (id, variant_id, ingredient_id, quantity) VALUES ($1, $2, $3, $4)",
                    )
                    .bind(new_uuid_v4())
                    .bind(target_id)
                    .bind(*ingredient_id)
                    .bind(*qty)
                    .execute(&mut *tx)
                    .await?;
                }
            }
            RecipeTargetType::Crust => {
                sqlx::query("DELETE FROM crust_recipes WHERE crust_id = $1")
                    .bind(target_id)
                    .execute(&mut *tx)
                    .await?;
                for (ingredient_id, qty) in &merged_qty {
                    sqlx::query(
                        "INSERT INTO crust_recipes (id, crust_id, ingredient_id, quantity) VALUES ($1, $2, $3, $4)",
                    )
                    .bind(new_uuid_v4())
                    .bind(target_id)
                    .bind(*ingredient_id)
                    .bind(*qty)
                    .execute(&mut *tx)
                    .await?;
                }
            }
            RecipeTargetType::Topping => {
                sqlx::query("DELETE FROM topping_recipes WHERE topping_id = $1")
                    .bind(target_id)
                    .execute(&mut *tx)
                    .await?;
                for (ingredient_id, qty) in &merged_qty {
                    sqlx::query(
                        "INSERT INTO topping_recipes (id, topping_id, ingredient_id, quantity) VALUES ($1, $2, $3, $4)",
                    )
                    .bind(new_uuid_v4())
                    .bind(target_id)
                    .bind(*ingredient_id)
                    .bind(*qty)
                    .execute(&mut *tx)
                    .await?;
                }
            }
        }

        let lines_map =
            load_recipe_lines_for_target(&mut tx, request.target_type, target_id).await?;
        let binding = RecipeBindingDto {
            target_type: request.target_type,
            target_id: target_id.to_string(),
            target_label,
            ingredients: lines_map,
        };

        let after_val = serde_json::to_value(&binding).ok();
        let entity_id = target_id.to_string();
        record_audit_log(
            &mut tx,
            audit,
            "RECIPE_UPDATED",
            "recipe",
            &entity_id,
            None,
            after_val.as_ref(),
        )
        .await?;

        tx.commit().await?;
        Ok(binding)
    }

    pub async fn compute_required_ingredients_for_items(
        conn: &mut PgConnection,
        items: &[CheckoutRecipeLineDemandInput],
    ) -> Result<HashMap<Uuid, i64>, ApiError> {
        let mut required: HashMap<Uuid, i64> = HashMap::new();
        if items.is_empty() {
            return Ok(required);
        }

        let variant_ids: Vec<Uuid> = items.iter().map(|i| i.variant_id).collect();
        let crust_ids: Vec<Uuid> = items.iter().map(|i| i.crust_id).collect();
        let topping_ids: Vec<Uuid> = items
            .iter()
            .flat_map(|i| i.topping_ids.iter().copied())
            .collect();

        let variant_map =
            load_demand_map(&mut *conn, "variant_recipes", "variant_id", &variant_ids).await?;
        let crust_map =
            load_demand_map(&mut *conn, "crust_recipes", "crust_id", &crust_ids).await?;
        let topping_map = if topping_ids.is_empty() {
            HashMap::new()
        } else {
            load_demand_map(&mut *conn, "topping_recipes", "topping_id", &topping_ids).await?
        };

        for item in items {
            let qty = i64::from(item.quantity);
            if let Some(lines) = variant_map.get(&item.variant_id) {
                for (ing_id, per_unit) in lines {
                    *required.entry(*ing_id).or_insert(0) += (*per_unit) * qty;
                }
            }
            if let Some(lines) = crust_map.get(&item.crust_id) {
                for (ing_id, per_unit) in lines {
                    *required.entry(*ing_id).or_insert(0) += (*per_unit) * qty;
                }
            }
            for tid in &item.topping_ids {
                if let Some(lines) = topping_map.get(tid) {
                    for (ing_id, per_unit) in lines {
                        *required.entry(*ing_id).or_insert(0) += (*per_unit) * qty;
                    }
                }
            }
        }

        Ok(required)
    }
}

async fn load_demand_map(
    conn: &mut PgConnection,
    table: &str,
    fk_col: &str,
    ids: &[Uuid],
) -> Result<HashMap<Uuid, Vec<(Uuid, i64)>>, ApiError> {
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let sql = format!(
        "SELECT {fk_col} AS target_id, ingredient_id, quantity FROM {table} WHERE {fk_col} = ANY($1)"
    );
    let rows = sqlx::query(&sql).bind(ids).fetch_all(&mut *conn).await?;
    let mut map: HashMap<Uuid, Vec<(Uuid, i64)>> = HashMap::new();
    for r in rows {
        let target_id: Uuid = r.try_get("target_id")?;
        let ing_id: Uuid = r.try_get("ingredient_id")?;
        let qty: i32 = r.try_get("quantity")?;
        map.entry(target_id)
            .or_default()
            .push((ing_id, i64::from(qty)));
    }
    Ok(map)
}

async fn load_recipe_lines(
    conn: &mut PgConnection,
    target_type: RecipeTargetType,
) -> Result<HashMap<Uuid, Vec<RecipeIngredientLineDto>>, ApiError> {
    let (table, fk_col) = match target_type {
        RecipeTargetType::Variant => ("variant_recipes", "variant_id"),
        RecipeTargetType::Crust => ("crust_recipes", "crust_id"),
        RecipeTargetType::Topping => ("topping_recipes", "topping_id"),
    };
    let sql = format!(
        r#"
        SELECT r.id, r.{fk_col} AS target_id, r.ingredient_id, r.quantity,
               i.name AS ingredient_name, i.unit
        FROM {table} r
        JOIN ingredients i ON i.id = r.ingredient_id
        ORDER BY i.name ASC
        "#
    );
    let rows = sqlx::query(&sql).fetch_all(&mut *conn).await?;
    let mut grouped: HashMap<Uuid, Vec<RecipeIngredientLineDto>> = HashMap::new();
    for r in rows {
        let target_id: Uuid = r.try_get("target_id")?;
        grouped
            .entry(target_id)
            .or_default()
            .push(row_to_recipe_line(&r)?);
    }
    Ok(grouped)
}

async fn load_recipe_lines_for_target(
    conn: &mut PgConnection,
    target_type: RecipeTargetType,
    target_id: Uuid,
) -> Result<Vec<RecipeIngredientLineDto>, ApiError> {
    let (table, fk_col) = match target_type {
        RecipeTargetType::Variant => ("variant_recipes", "variant_id"),
        RecipeTargetType::Crust => ("crust_recipes", "crust_id"),
        RecipeTargetType::Topping => ("topping_recipes", "topping_id"),
    };
    let sql = format!(
        r#"
        SELECT r.id, r.ingredient_id, r.quantity, i.name AS ingredient_name, i.unit
        FROM {table} r
        JOIN ingredients i ON i.id = r.ingredient_id
        WHERE r.{fk_col} = $1
        ORDER BY i.name ASC
        "#
    );
    let rows = sqlx::query(&sql)
        .bind(target_id)
        .fetch_all(&mut *conn)
        .await?;
    rows.iter().map(row_to_recipe_line).collect()
}

fn row_to_recipe_line(row: &sqlx::postgres::PgRow) -> Result<RecipeIngredientLineDto, ApiError> {
    let id: Uuid = row.try_get("id")?;
    let ingredient_id: Uuid = row.try_get("ingredient_id")?;
    let unit_raw: String = row.try_get("unit")?;
    let unit = IngredientUnit::from_db_str(&unit_raw)?;
    Ok(RecipeIngredientLineDto {
        id: id.to_string(),
        ingredient_id: ingredient_id.to_string(),
        ingredient_name: row.try_get("ingredient_name")?,
        unit,
        quantity: row.try_get("quantity")?,
    })
}

fn build_bindings(
    rows: Vec<sqlx::postgres::PgRow>,
    lines: &HashMap<Uuid, Vec<RecipeIngredientLineDto>>,
    target_type: RecipeTargetType,
) -> Result<Vec<RecipeBindingDto>, ApiError> {
    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        let target_id: Uuid = row.try_get("target_id")?;
        let target_label: String = row.try_get("target_label")?;
        out.push(RecipeBindingDto {
            target_type,
            target_id: target_id.to_string(),
            target_label,
            ingredients: lines.get(&target_id).cloned().unwrap_or_default(),
        });
    }
    Ok(out)
}
