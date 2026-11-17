use chrono::{DateTime, Duration, NaiveDate, Utc};
use pizzora_db::{IngredientUnit, InventoryService};
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use crate::dto::{
    CouponRoiDto, DailySalesBucketDto, OperationalStockMovementDto, OperationsReportResponse,
    SalesReportResponse, TopSellingProductDto,
};
use crate::error::{now_iso8601, ApiError};
use crate::services::slots::{ist_offset, today_ist_date};

const MAX_REPORT_SPAN_DAYS: i64 = 366;

#[derive(Clone)]
pub struct ReportingService {
    pool: PgPool,
    inventory_service: InventoryService,
}

impl ReportingService {
    pub fn new(pool: PgPool) -> Self {
        let inventory_service = InventoryService::new(pool.clone());
        Self {
            pool,
            inventory_service,
        }
    }

    pub async fn get_sales_report(
        &self,
        start_date_raw: Option<&str>,
        end_date_raw: Option<&str>,
    ) -> Result<SalesReportResponse, ApiError> {
        let (start_date, end_date) = resolve_ist_window(start_date_raw, end_date_raw)?;
        let (start_ts, end_exclusive_ts) = ist_bounds_utc(start_date, end_date)?;

        let mut tx = self.pool.begin().await?;

        let summary_row = sqlx::query(
            r#"
            WITH scoped_orders AS (
              SELECT o.id,
                     o.status,
                     o.subtotal_paise,
                     o.discount_paise,
                     o.delivery_fee_paise,
                     o.tax_paise,
                     o.total_paise,
                     COALESCE(p.captured_paise, 0)::bigint AS captured_paise,
                     COALESCE(p.refunded_paise, 0)::bigint AS refunded_paise,
                     COALESCE((
                       SELECT SUM(r.amount_paise)::bigint
                       FROM refunds r
                       WHERE r.order_id = o.id AND r.status IN ('PENDING', 'PROCESSING')
                     ), 0)::bigint AS pending_refunds_paise
              FROM orders o
              LEFT JOIN payments p ON p.order_id = o.id
              WHERE o.created_at >= $1 AND o.created_at < $2
            ),
            revenue_orders AS (
              SELECT *
              FROM scoped_orders
              WHERE status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
                AND (status <> 'CANCELLED' OR captured_paise > 0)
            )
            SELECT
              (SELECT COUNT(*)::int FROM revenue_orders) AS order_count,
              (SELECT COUNT(*)::int FROM scoped_orders WHERE status = 'CANCELLED') AS cancelled_order_count,
              COALESCE(SUM(subtotal_paise), 0)::bigint AS subtotal_paise,
              COALESCE(SUM(discount_paise), 0)::bigint AS discount_paise,
              COALESCE(SUM(delivery_fee_paise), 0)::bigint AS delivery_fee_paise,
              COALESCE(SUM(tax_paise), 0)::bigint AS tax_paise,
              COALESCE(SUM(total_paise), 0)::bigint AS gross_sales_paise,
              COALESCE(SUM(refunded_paise), 0)::bigint AS refunded_paise,
              COALESCE(SUM(pending_refunds_paise), 0)::bigint AS pending_refunds_paise
            FROM revenue_orders
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_one(&mut *tx)
        .await?;

        let order_count: i32 = summary_row.try_get("order_count")?;
        let cancelled_order_count: i32 = summary_row.try_get("cancelled_order_count")?;
        let subtotal_paise: i64 = summary_row.try_get("subtotal_paise")?;
        let discount_paise: i64 = summary_row.try_get("discount_paise")?;
        let delivery_fee_paise: i64 = summary_row.try_get("delivery_fee_paise")?;
        let tax_paise: i64 = summary_row.try_get("tax_paise")?;
        let gross_sales_paise: i64 = summary_row.try_get("gross_sales_paise")?;
        let refunded_paise: i64 = summary_row.try_get("refunded_paise")?;
        let pending_refunds_paise: i64 = summary_row.try_get("pending_refunds_paise")?;

        let daily_rows = sqlx::query(
            r#"
            SELECT
              TO_CHAR((o.created_at AT TIME ZONE 'Asia/Kolkata')::date, 'YYYY-MM-DD') AS ist_date,
              COUNT(*)::int AS order_count,
              COALESCE(SUM(o.total_paise), 0)::bigint AS gross_sales_paise,
              COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0)::bigint AS refunded_paise,
              GREATEST(0, COALESCE(SUM(o.total_paise), 0) - COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0))::bigint AS net_sales_paise,
              COALESCE(SUM(o.tax_paise), 0)::bigint AS tax_paise
            FROM orders o
            LEFT JOIN payments p ON p.order_id = o.id
            WHERE o.created_at >= $1 AND o.created_at < $2
              AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
              AND (o.status <> 'CANCELLED' OR COALESCE(p.captured_paise, 0) > 0)
            GROUP BY (o.created_at AT TIME ZONE 'Asia/Kolkata')::date
            ORDER BY (o.created_at AT TIME ZONE 'Asia/Kolkata')::date ASC
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_all(&mut *tx)
        .await?;

        let mut daily_breakdown = Vec::with_capacity(daily_rows.len());
        for row in daily_rows {
            daily_breakdown.push(DailySalesBucketDto {
                date: row.try_get("ist_date")?,
                order_count: row.try_get("order_count")?,
                gross_sales_paise: row.try_get("gross_sales_paise")?,
                refunded_paise: row.try_get("refunded_paise")?,
                net_sales_paise: row.try_get("net_sales_paise")?,
                tax_paise: row.try_get("tax_paise")?,
            });
        }

        let top_rows = sqlx::query(
            r#"
            SELECT
              oi.product_id,
              oi.product_name_snapshot,
              COALESCE(SUM(oi.quantity), 0)::int AS quantity_sold,
              COALESCE(SUM(oi.line_total_paise), 0)::bigint AS gross_revenue_paise
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.created_at >= $1 AND o.created_at < $2
              AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED')
            GROUP BY oi.product_id, oi.product_name_snapshot
            ORDER BY quantity_sold DESC, gross_revenue_paise DESC, oi.product_name_snapshot ASC
            LIMIT 10
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_all(&mut *tx)
        .await?;

        let mut top_products = Vec::with_capacity(top_rows.len());
        for row in top_rows {
            let pid: Uuid = row.try_get("product_id")?;
            top_products.push(TopSellingProductDto {
                product_id: pid.to_string(),
                product_name: row.try_get("product_name_snapshot")?,
                quantity_sold: row.try_get("quantity_sold")?,
                gross_revenue_paise: row.try_get("gross_revenue_paise")?,
            });
        }

        let coupon_rows = sqlx::query(
            r#"
            SELECT
              c.id AS coupon_id,
              c.code,
              COUNT(*)::int AS redemption_count,
              COALESCE(SUM(cr.discount_paise), 0)::bigint AS total_discount_paise,
              COALESCE(SUM(o.total_paise), 0)::bigint AS gross_order_revenue_paise,
              GREATEST(0, COALESCE(SUM(o.total_paise), 0) - COALESCE(SUM(COALESCE(p.refunded_paise, 0)), 0))::bigint AS net_order_revenue_paise
            FROM coupon_redemptions cr
            JOIN coupons c ON c.id = cr.coupon_id
            JOIN orders o ON o.id = cr.order_id
            LEFT JOIN payments p ON p.order_id = o.id
            WHERE o.created_at >= $1 AND o.created_at < $2
              AND o.status NOT IN ('PENDING_PAYMENT', 'EXPIRED')
              AND (o.status <> 'CANCELLED' OR COALESCE(p.captured_paise, 0) > 0)
            GROUP BY c.id, c.code
            ORDER BY redemption_count DESC, net_order_revenue_paise DESC
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_all(&mut *tx)
        .await?;

        let mut coupon_performance = Vec::with_capacity(coupon_rows.len());
        for row in coupon_rows {
            let cid: Uuid = row.try_get("coupon_id")?;
            coupon_performance.push(CouponRoiDto {
                coupon_id: cid.to_string(),
                code: row.try_get("code")?,
                redemption_count: row.try_get("redemption_count")?,
                total_discount_paise: row.try_get("total_discount_paise")?,
                gross_order_revenue_paise: row.try_get("gross_order_revenue_paise")?,
                net_order_revenue_paise: row.try_get("net_order_revenue_paise")?,
            });
        }

        tx.commit().await?;

        let cgst_paise = tax_paise / 2;
        let sgst_paise = tax_paise - cgst_paise;
        let net_sales_paise = (gross_sales_paise - refunded_paise).max(0);

        Ok(SalesReportResponse {
            start_date: start_date.format("%Y-%m-%d").to_string(),
            end_date: end_date.format("%Y-%m-%d").to_string(),
            timezone: "Asia/Kolkata".to_owned(),
            order_count,
            cancelled_order_count,
            subtotal_paise,
            discount_paise,
            delivery_fee_paise,
            tax_paise,
            cgst_paise,
            sgst_paise,
            gross_sales_paise,
            refunded_paise,
            pending_refunds_paise,
            net_sales_paise,
            daily_breakdown,
            top_products,
            coupon_performance,
            server_now: now_iso8601(),
        })
    }

    pub async fn get_operations_report(
        &self,
        start_date_raw: Option<&str>,
        end_date_raw: Option<&str>,
    ) -> Result<OperationsReportResponse, ApiError> {
        let (start_date, end_date) = resolve_ist_window(start_date_raw, end_date_raw)?;
        let (start_ts, end_exclusive_ts) = ist_bounds_utc(start_date, end_date)?;

        let all_inventory = self.inventory_service.list_inventory().await?;
        let low_stock_items: Vec<_> = all_inventory
            .into_iter()
            .filter(|item| item.is_low_stock)
            .collect();

        let mut tx = self.pool.begin().await?;
        let prep_sla_minutes: i32 =
            sqlx::query_scalar("SELECT default_prep_mins FROM restaurant_settings WHERE id = 1")
                .fetch_optional(&mut *tx)
                .await?
                .unwrap_or(20);

        let stats_row = sqlx::query(
            r#"
            WITH scoped AS (
              SELECT o.*,
                     CASE
                       WHEN o.ready_at IS NOT NULL THEN
                         GREATEST(0.0, EXTRACT(EPOCH FROM (o.ready_at - COALESCE(o.prep_started_at, o.accepted_at, o.created_at))) / 60.0)
                       ELSE NULL
                     END AS prep_mins,
                     CASE
                       WHEN o.completed_at IS NOT NULL THEN
                         GREATEST(0.0, EXTRACT(EPOCH FROM (o.completed_at - o.created_at)) / 60.0)
                       ELSE NULL
                     END AS completion_mins
              FROM orders o
              WHERE o.created_at >= $1 AND o.created_at < $2
            )
            SELECT
              COUNT(*)::int AS total_orders,
              COUNT(*) FILTER (WHERE status = 'COMPLETED')::int AS completed_orders,
              COUNT(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled_orders,
              COUNT(*) FILTER (WHERE status = 'EXPIRED')::int AS expired_orders,
              COUNT(*) FILTER (WHERE fulfillment_type = 'DELIVERY' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS delivery_orders,
              COUNT(*) FILTER (WHERE fulfillment_type = 'PICKUP' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS pickup_orders,
              COUNT(*) FILTER (WHERE schedule_type = 'SCHEDULED' AND status NOT IN ('PENDING_PAYMENT', 'EXPIRED'))::int AS scheduled_orders,
              COUNT(prep_mins)::int AS measured_prep_orders,
              COALESCE(AVG(prep_mins), 0.0)::double precision AS avg_prep_minutes,
              COALESCE(MAX(prep_mins), 0.0)::double precision AS max_prep_minutes,
              COALESCE(AVG(completion_mins), 0.0)::double precision AS avg_completion_minutes,
              COUNT(*) FILTER (WHERE prep_mins IS NOT NULL AND prep_mins > $3)::int AS sla_breached_count
            FROM scoped
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .bind(f64::from(prep_sla_minutes))
        .fetch_one(&mut *tx)
        .await?;

        let total_orders: i32 = stats_row.try_get("total_orders")?;
        let completed_orders: i32 = stats_row.try_get("completed_orders")?;
        let cancelled_orders: i32 = stats_row.try_get("cancelled_orders")?;
        let expired_orders: i32 = stats_row.try_get("expired_orders")?;
        let delivery_orders: i32 = stats_row.try_get("delivery_orders")?;
        let pickup_orders: i32 = stats_row.try_get("pickup_orders")?;
        let scheduled_orders: i32 = stats_row.try_get("scheduled_orders")?;
        let measured_prep_orders: i32 = stats_row.try_get("measured_prep_orders")?;
        let avg_prep_minutes = round_two_decimals(stats_row.try_get::<f64, _>("avg_prep_minutes")?);
        let max_prep_minutes = round_two_decimals(stats_row.try_get::<f64, _>("max_prep_minutes")?);
        let avg_completion_minutes =
            round_two_decimals(stats_row.try_get::<f64, _>("avg_completion_minutes")?);
        let sla_breached_count: i32 = stats_row.try_get("sla_breached_count")?;

        let sla_compliance_percent = if measured_prep_orders == 0 {
            100.0
        } else {
            let compliant = (measured_prep_orders - sla_breached_count).max(0);
            round_two_decimals((compliant as f64 * 100.0) / (measured_prep_orders as f64))
        };

        let stock_summary_row = sqlx::query(
            r#"
            SELECT
              COALESCE(SUM(delta_on_hand) FILTER (WHERE reason = 'RESTOCK'), 0)::bigint AS restocked_units,
              COALESCE(ABS(SUM(delta_on_hand) FILTER (WHERE reason = 'COMMIT')), 0)::bigint AS consumed_units,
              COALESCE(SUM(delta_on_hand) FILTER (WHERE reason = 'CANCEL_RESTORE'), 0)::bigint AS restored_units,
              COALESCE(ABS(SUM(delta_on_hand) FILTER (WHERE reason = 'MANUAL_ADJUST' AND delta_on_hand < 0)), 0)::bigint AS wastage_units
            FROM inventory_ledger
            WHERE created_at >= $1 AND created_at < $2
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_one(&mut *tx)
        .await?;

        let restocked_units: i64 = stock_summary_row.try_get("restocked_units")?;
        let consumed_units: i64 = stock_summary_row.try_get("consumed_units")?;
        let restored_units: i64 = stock_summary_row.try_get("restored_units")?;
        let wastage_units: i64 = stock_summary_row.try_get("wastage_units")?;

        let movement_rows = sqlx::query(
            r#"
            SELECT
              i.id AS ingredient_id,
              i.name AS ingredient_name,
              i.unit,
              COALESCE(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'RESTOCK'), 0)::bigint AS restocked_qty,
              COALESCE(ABS(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'COMMIT')), 0)::bigint AS consumed_qty,
              COALESCE(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'CANCEL_RESTORE'), 0)::bigint AS restored_qty,
              COALESCE(ABS(SUM(il.delta_on_hand) FILTER (WHERE il.reason = 'MANUAL_ADJUST' AND il.delta_on_hand < 0)), 0)::bigint AS wastage_qty
            FROM inventory_ledger il
            JOIN ingredients i ON i.id = il.ingredient_id
            WHERE il.created_at >= $1 AND il.created_at < $2
            GROUP BY i.id, i.name, i.unit
            ORDER BY consumed_qty DESC, wastage_qty DESC, restocked_qty DESC, i.name ASC
            LIMIT 15
            "#,
        )
        .bind(start_ts)
        .bind(end_exclusive_ts)
        .fetch_all(&mut *tx)
        .await?;

        let mut stock_movements = Vec::with_capacity(movement_rows.len());
        for row in movement_rows {
            let ing_id: Uuid = row.try_get("ingredient_id")?;
            let unit_raw: String = row.try_get("unit")?;
            stock_movements.push(OperationalStockMovementDto {
                ingredient_id: ing_id.to_string(),
                ingredient_name: row.try_get("ingredient_name")?,
                unit: IngredientUnit::from_db_str(&unit_raw)?,
                restocked_qty: row.try_get("restocked_qty")?,
                consumed_qty: row.try_get("consumed_qty")?,
                restored_qty: row.try_get("restored_qty")?,
                wastage_qty: row.try_get("wastage_qty")?,
            });
        }

        tx.commit().await?;

        Ok(OperationsReportResponse {
            start_date: start_date.format("%Y-%m-%d").to_string(),
            end_date: end_date.format("%Y-%m-%d").to_string(),
            timezone: "Asia/Kolkata".to_owned(),
            prep_sla_minutes,
            total_orders,
            completed_orders,
            cancelled_orders,
            expired_orders,
            delivery_orders,
            pickup_orders,
            scheduled_orders,
            measured_prep_orders,
            avg_prep_minutes,
            max_prep_minutes,
            avg_completion_minutes,
            sla_breached_count,
            sla_compliance_percent,
            restocked_units,
            consumed_units,
            restored_units,
            wastage_units,
            stock_movements,
            low_stock_items,
            server_now: now_iso8601(),
        })
    }
}

fn resolve_ist_window(
    start_raw: Option<&str>,
    end_raw: Option<&str>,
) -> Result<(NaiveDate, NaiveDate), ApiError> {
    let clean_start = start_raw.map(str::trim).filter(|s| !s.is_empty());
    let clean_end = end_raw.map(str::trim).filter(|s| !s.is_empty());
    let today_ist = today_ist_date();

    let (start_date, end_date) = match (clean_start, clean_end) {
        (None, None) => (today_ist - Duration::days(6), today_ist),
        (Some(s), None) => {
            let d = parse_report_date(s, "start_date")?;
            (d, d)
        }
        (None, Some(e)) => {
            let d = parse_report_date(e, "end_date")?;
            (d, d)
        }
        (Some(s), Some(e)) => (
            parse_report_date(s, "start_date")?,
            parse_report_date(e, "end_date")?,
        ),
    };

    if start_date > end_date {
        return Err(ApiError::validation(
            "INVALID_DATE_RANGE",
            format!("start_date ({start_date}) cannot be after end_date ({end_date})."),
        ));
    }

    let span_days = (end_date - start_date).num_days() + 1;
    if span_days > MAX_REPORT_SPAN_DAYS {
        return Err(ApiError::validation(
            "DATE_RANGE_TOO_LARGE",
            format!("Report window cannot exceed {MAX_REPORT_SPAN_DAYS} days."),
        ));
    }

    Ok((start_date, end_date))
}

fn parse_report_date(raw: &str, field: &str) -> Result<NaiveDate, ApiError> {
    NaiveDate::parse_from_str(raw.trim(), "%Y-%m-%d").map_err(|_| {
        ApiError::validation(
            "INVALID_DATE",
            format!("{field} must be formatted as YYYY-MM-DD (Asia/Kolkata)."),
        )
    })
}

fn ist_bounds_utc(
    start_date: NaiveDate,
    end_date: NaiveDate,
) -> Result<(DateTime<Utc>, DateTime<Utc>), ApiError> {
    let offset = ist_offset();
    let start_naive = start_date
        .and_hms_opt(0, 0, 0)
        .ok_or_else(ApiError::internal)?;
    let start_utc = start_naive
        .and_local_timezone(offset)
        .single()
        .ok_or_else(ApiError::internal)?
        .with_timezone(&Utc);

    let end_exclusive_naive = (end_date + Duration::days(1))
        .and_hms_opt(0, 0, 0)
        .ok_or_else(ApiError::internal)?;
    let end_exclusive_utc = end_exclusive_naive
        .and_local_timezone(offset)
        .single()
        .ok_or_else(ApiError::internal)?
        .with_timezone(&Utc);

    Ok((start_utc, end_exclusive_utc))
}

fn round_two_decimals(value: f64) -> f64 {
    if !value.is_finite() {
        return 0.0;
    }
    (value * 100.0).round() / 100.0
}
