use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DomainError;
use crate::money::Money;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiscountType {
    PercentBps,
    FlatPaise,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PricedLineInput {
    pub unit_price_paise: i64,
    pub quantity: i32,
    pub is_taxable: bool,
}

/// Extended input for unit price calculation with all Phase 8 modifiers.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PricedLineInputExtended {
    pub variant_price_paise: i64,
    pub crust_extra_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings: Vec<StandardToppingSelection>,
    pub extra_topping_extras: Vec<i64>,
    pub half_and_half: Option<HalfAndHalfInput>,
    pub quantity: i32,
}

/// A standard topping selection with its pricing metadata.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StandardToppingSelection {
    pub id: Uuid,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_removed: bool,
}

/// Half-and-half configuration input.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HalfAndHalfInput {
    pub left_topping_extras: Vec<i64>,
    pub right_topping_extras: Vec<i64>,
    pub surcharge_paise: i64,
}

/// Half-and-half price breakdown for transparency.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HalfAndHalfBreakdown {
    pub left_half_base_paise: i64,
    pub right_half_base_paise: i64,
    pub average_paise: i64,
    pub surcharge_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings_extra_paise: i64,
    pub unit_price_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CouponCalculationSpec {
    pub discount_type: DiscountType,
    pub discount_value: i64,
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PriceBreakdown {
    pub subtotal_paise: i64,
    pub taxable_subtotal_paise: i64,
    pub discount_paise: i64,
    pub taxable_amount_paise: i64,
    pub delivery_fee_paise: i64,
    pub gst_bps: i32,
    pub tax_paise: i64,
    pub cgst_paise: i64,
    pub sgst_paise: i64,
    pub total_paise: i64,
}

pub struct PricingEngine;

impl PricingEngine {
    const BPS_DENOMINATOR: i128 = 10_000;
    const HALF_BPS: i128 = 5_000;

    pub fn calculate_line_total_paise(
        unit_price_paise: i64,
        quantity: i32,
    ) -> Result<i64, DomainError> {
        if unit_price_paise < 0 {
            return Err(DomainError::NegativeAmount("unit_price_paise"));
        }
        if quantity <= 0 {
            return Err(DomainError::NonPositiveQuantity(quantity));
        }
        unit_price_paise
            .checked_mul(i64::from(quantity))
            .ok_or(DomainError::ArithmeticOverflow(
                "calculate_line_total_paise",
            ))
    }

    pub fn calculate_discount_paise(
        subtotal_paise: i64,
        coupon: Option<&CouponCalculationSpec>,
    ) -> Result<i64, DomainError> {
        if subtotal_paise < 0 {
            return Err(DomainError::NegativeAmount("subtotal_paise"));
        }
        let Some(coupon) = coupon else {
            return Ok(0);
        };
        if !coupon.is_active || subtotal_paise == 0 {
            return Ok(0);
        }
        if coupon.discount_value <= 0 {
            return Err(DomainError::NonPositiveValue("discount_value"));
        }
        if coupon.min_order_paise < 0 {
            return Err(DomainError::NegativeAmount("min_order_paise"));
        }
        if let Some(max_disc) = coupon.max_discount_paise {
            if max_disc <= 0 {
                return Err(DomainError::NonPositiveValue("max_discount_paise"));
            }
        }

        if subtotal_paise < coupon.min_order_paise {
            return Ok(0);
        }

        let raw_discount = match coupon.discount_type {
            DiscountType::PercentBps => {
                if coupon.discount_value > 10_000 {
                    return Err(DomainError::RangeViolation {
                        field: "discount_value",
                        value: coupon.discount_value,
                        min: 1,
                        max: 10_000,
                    });
                }
                let numerator = i128::from(subtotal_paise)
                    .checked_mul(i128::from(coupon.discount_value))
                    .and_then(|v| v.checked_add(Self::HALF_BPS))
                    .ok_or(DomainError::ArithmeticOverflow("calculate_discount_paise"))?;
                let val = numerator / Self::BPS_DENOMINATOR;
                i64::try_from(val)
                    .map_err(|_| DomainError::ArithmeticOverflow("calculate_discount_paise"))?
            }
            DiscountType::FlatPaise => coupon.discount_value,
        };

        let after_max_cap = match coupon.max_discount_paise {
            Some(max_disc) => raw_discount.min(max_disc),
            None => raw_discount,
        };

        Ok(after_max_cap.min(subtotal_paise).max(0))
    }

    pub fn calculate_quote(
        lines: &[PricedLineInput],
        gst_bps: i32,
        delivery_fee_paise: i64,
        coupon: Option<&CouponCalculationSpec>,
    ) -> Result<PriceBreakdown, DomainError> {
        if !(0..=2800).contains(&gst_bps) {
            return Err(DomainError::RangeViolation {
                field: "gst_bps",
                value: i64::from(gst_bps),
                min: 0,
                max: 2800,
            });
        }
        if delivery_fee_paise < 0 {
            return Err(DomainError::NegativeAmount("delivery_fee_paise"));
        }

        let mut subtotal_paise: i64 = 0;
        let mut taxable_subtotal_paise: i64 = 0;

        for line in lines {
            let line_total =
                Self::calculate_line_total_paise(line.unit_price_paise, line.quantity)?;
            subtotal_paise = subtotal_paise
                .checked_add(line_total)
                .ok_or(DomainError::ArithmeticOverflow("subtotal_paise"))?;
            if line.is_taxable {
                taxable_subtotal_paise = taxable_subtotal_paise
                    .checked_add(line_total)
                    .ok_or(DomainError::ArithmeticOverflow("taxable_subtotal_paise"))?;
            }
        }

        let discount_paise = Self::calculate_discount_paise(subtotal_paise, coupon)?;

        let taxable_amount_paise = if taxable_subtotal_paise == 0 || subtotal_paise == 0 {
            0
        } else if taxable_subtotal_paise == subtotal_paise {
            subtotal_paise - discount_paise
        } else {
            let subtotal_i128 = i128::from(subtotal_paise);
            let numerator = i128::from(discount_paise)
                .checked_mul(i128::from(taxable_subtotal_paise))
                .and_then(|v| v.checked_add(subtotal_i128 / 2))
                .ok_or(DomainError::ArithmeticOverflow("taxable_discount"))?;
            let taxable_discount = i64::try_from(numerator / subtotal_i128)
                .map_err(|_| DomainError::ArithmeticOverflow("taxable_discount"))?;
            (taxable_subtotal_paise - taxable_discount).max(0)
        };

        let tax_paise = Money::bps_of(taxable_amount_paise, gst_bps)?;
        let cgst_paise = tax_paise / 2;
        let sgst_paise = tax_paise - cgst_paise;

        let net_items_total = (subtotal_paise - discount_paise).max(0);
        let total_paise = net_items_total
            .checked_add(delivery_fee_paise)
            .and_then(|v| v.checked_add(tax_paise))
            .ok_or(DomainError::ArithmeticOverflow("total_paise"))?;

        Ok(PriceBreakdown {
            subtotal_paise,
            taxable_subtotal_paise,
            discount_paise,
            taxable_amount_paise,
            delivery_fee_paise,
            gst_bps,
            tax_paise,
            cgst_paise,
            sgst_paise,
            total_paise,
        })
    }

    /// Calculate extra price from standard topping selections.
    /// Only non-default selected toppings add their extra_price_paise.
    /// Default toppings (kept or removed) contribute 0 (zero credit, never negative).
    pub fn calculate_standard_toppings_extra_paise(
        selections: &[StandardToppingSelection],
    ) -> Result<i64, DomainError> {
        let mut total: i64 = 0;
        for sel in selections {
            if !sel.is_default && !sel.is_removed {
                if sel.extra_price_paise < 0 {
                    return Err(DomainError::NegativeAmount(
                        "standard_topping_extra_price_paise",
                    ));
                }
                total = total.checked_add(sel.extra_price_paise).ok_or(
                    DomainError::ArithmeticOverflow("calculate_standard_toppings_extra_paise"),
                )?;
            }
            // is_default == true (kept or removed) -> contributes 0
            // is_removed == true for non-default -> contributes 0 (zero credit)
        }
        Ok(total)
    }

    /// Calculate unit price for a line item with all Phase 8 modifiers.
    /// Supports both linear and half-and-half pricing.
    /// All arithmetic is checked; overflow returns DomainError::ArithmeticOverflow.
    #[allow(clippy::too_many_arguments)]
    pub fn calculate_unit_price_paise(
        variant_price_paise: i64,
        crust_extra_paise: i64,
        cheese_extra_paise: i64,
        sauce_extra_paise: i64,
        standard_toppings_extra_paise: i64,
        extra_topping_extras: &[i64],
        half_and_half: Option<&HalfAndHalfInput>,
    ) -> Result<i64, DomainError> {
        // Validate all inputs are non-negative
        if variant_price_paise < 0 {
            return Err(DomainError::NegativeAmount("variant_price_paise"));
        }
        if crust_extra_paise < 0 {
            return Err(DomainError::NegativeAmount("crust_extra_paise"));
        }
        if cheese_extra_paise < 0 {
            return Err(DomainError::NegativeAmount("cheese_extra_paise"));
        }
        if sauce_extra_paise < 0 {
            return Err(DomainError::NegativeAmount("sauce_extra_paise"));
        }
        if standard_toppings_extra_paise < 0 {
            return Err(DomainError::NegativeAmount("standard_toppings_extra_paise"));
        }
        for extra in extra_topping_extras {
            if *extra < 0 {
                return Err(DomainError::NegativeAmount(
                    "extra_topping_extra_price_paise",
                ));
            }
        }

        match half_and_half {
            None => {
                // Linear pricing:
                // unit_price = variant + crust + cheese + sauce + std_extra + sum(extra topping extras)
                let mut price = variant_price_paise;
                price = price
                    .checked_add(crust_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_crust"))?;
                price = price
                    .checked_add(cheese_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_cheese"))?;
                price = price
                    .checked_add(sauce_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_sauce"))?;
                price = price
                    .checked_add(standard_toppings_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_std"))?;
                for extra in extra_topping_extras {
                    price = price
                        .checked_add(*extra)
                        .ok_or(DomainError::ArithmeticOverflow(
                            "unit_price_add_extra_topping",
                        ))?;
                }
                Ok(price)
            }
            Some(hah) => {
                // Half-and-half pricing:
                // left_half_base  = variant + crust + Σ(left extras)
                // right_half_base = variant + crust + Σ(right extras)
                // average = ROUND_HALF_UP((left + right) / 2) = (left + right + 1) / 2 in i128
                // unit_price = average + surcharge + cheese + sauce + std_extra

                // Sum left extras
                let mut left_sum: i64 = 0;
                for extra in &hah.left_topping_extras {
                    if *extra < 0 {
                        return Err(DomainError::NegativeAmount("half_and_half_left_extra"));
                    }
                    left_sum = left_sum
                        .checked_add(*extra)
                        .ok_or(DomainError::ArithmeticOverflow("half_left_sum"))?;
                }

                // Sum right extras
                let mut right_sum: i64 = 0;
                for extra in &hah.right_topping_extras {
                    if *extra < 0 {
                        return Err(DomainError::NegativeAmount("half_and_half_right_extra"));
                    }
                    right_sum = right_sum
                        .checked_add(*extra)
                        .ok_or(DomainError::ArithmeticOverflow("half_right_sum"))?;
                }

                // left_half_base = variant + crust + left_sum
                let left_half_base = variant_price_paise
                    .checked_add(crust_extra_paise)
                    .and_then(|v| v.checked_add(left_sum))
                    .ok_or(DomainError::ArithmeticOverflow("left_half_base"))?;

                // right_half_base = variant + crust + right_sum
                let right_half_base = variant_price_paise
                    .checked_add(crust_extra_paise)
                    .and_then(|v| v.checked_add(right_sum))
                    .ok_or(DomainError::ArithmeticOverflow("right_half_base"))?;

                // average = ROUND_HALF_UP((left + right) / 2) = (left + right + 1) / 2
                let average = i128::from(left_half_base)
                    .checked_add(i128::from(right_half_base))
                    .and_then(|v| v.checked_add(1))
                    .and_then(|v| v.checked_div(2))
                    .ok_or(DomainError::ArithmeticOverflow("half_average"))?;

                let average_paise = i64::try_from(average)
                    .map_err(|_| DomainError::ArithmeticOverflow("half_average_i64"))?;

                // unit_price = average + surcharge + cheese + sauce + std_extra
                let mut price = average_paise;
                if hah.surcharge_paise < 0 {
                    return Err(DomainError::NegativeAmount("half_and_half_surcharge_paise"));
                }
                price = price
                    .checked_add(hah.surcharge_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_surcharge"))?;
                price = price
                    .checked_add(cheese_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_cheese"))?;
                price = price
                    .checked_add(sauce_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_sauce"))?;
                price = price
                    .checked_add(standard_toppings_extra_paise)
                    .ok_or(DomainError::ArithmeticOverflow("unit_price_add_std"))?;

                Ok(price)
            }
        }
    }

    /// Calculate unit price with full breakdown for half-and-half.
    pub fn calculate_unit_price_with_breakdown(
        variant_price_paise: i64,
        crust_extra_paise: i64,
        cheese_extra_paise: i64,
        sauce_extra_paise: i64,
        standard_toppings_extra_paise: i64,
        extra_topping_extras: &[i64],
        half_and_half: Option<&HalfAndHalfInput>,
    ) -> Result<(i64, Option<HalfAndHalfBreakdown>), DomainError> {
        let unit_price = Self::calculate_unit_price_paise(
            variant_price_paise,
            crust_extra_paise,
            cheese_extra_paise,
            sauce_extra_paise,
            standard_toppings_extra_paise,
            extra_topping_extras,
            half_and_half,
        )?;

        let breakdown = if let Some(hah) = half_and_half {
            // Recompute for breakdown with the same checked arithmetic as the
            // authoritative unit-price path.
            let mut left_sum: i64 = 0;
            for extra in &hah.left_topping_extras {
                left_sum = left_sum
                    .checked_add(*extra)
                    .ok_or(DomainError::ArithmeticOverflow("breakdown_left_sum"))?;
            }
            let mut right_sum: i64 = 0;
            for extra in &hah.right_topping_extras {
                right_sum = right_sum
                    .checked_add(*extra)
                    .ok_or(DomainError::ArithmeticOverflow("breakdown_right_sum"))?;
            }
            let left_half_base = variant_price_paise
                .checked_add(crust_extra_paise)
                .and_then(|v| v.checked_add(left_sum))
                .ok_or(DomainError::ArithmeticOverflow("breakdown_left_half_base"))?;
            let right_half_base = variant_price_paise
                .checked_add(crust_extra_paise)
                .and_then(|v| v.checked_add(right_sum))
                .ok_or(DomainError::ArithmeticOverflow("breakdown_right_half_base"))?;
            let average = i128::from(left_half_base)
                .checked_add(i128::from(right_half_base))
                .and_then(|v| v.checked_add(1))
                .and_then(|v| v.checked_div(2))
                .ok_or(DomainError::ArithmeticOverflow("breakdown_half_average"))?;
            let average_paise = i64::try_from(average)
                .map_err(|_| DomainError::ArithmeticOverflow("breakdown_half_average_i64"))?;

            Some(HalfAndHalfBreakdown {
                left_half_base_paise: left_half_base,
                right_half_base_paise: right_half_base,
                average_paise,
                surcharge_paise: hah.surcharge_paise,
                cheese_extra_paise,
                sauce_extra_paise,
                standard_toppings_extra_paise,
                unit_price_paise: unit_price,
            })
        } else {
            None
        };

        Ok((unit_price, breakdown))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CouponRuleInput {
    pub code: String,
    pub discount_type: DiscountType,
    pub discount_value: i64,
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    pub max_uses: Option<i32>,
    pub max_uses_per_user: i32,
    pub usage_count: i32,
    pub active_global_reservations: i32,
    pub user_used_count: i32,
    pub starts_at_epoch_ms: i64,
    pub ends_at_epoch_ms: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CouponListingEvaluation {
    pub remaining_global_uses: Option<i32>,
    pub remaining_user_uses: i32,
    pub is_eligible: bool,
    pub ineligible_reason: Option<String>,
    pub estimated_discount_paise: i64,
}

pub struct CouponEvaluator;

impl CouponEvaluator {
    pub fn normalize_code(raw: &str) -> Result<String, DomainError> {
        let normalized = raw.trim().to_ascii_uppercase();
        let len = normalized.len();
        let valid_chars = normalized
            .bytes()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_' || b == b'-');
        if !(3..=32).contains(&len) || !valid_chars {
            return Err(DomainError::Validation {
                code: "INVALID_COUPON_CODE",
                message: "Coupon code must be 3-32 uppercase alphanumeric, '_' or '-' characters."
                    .to_owned(),
            });
        }
        Ok(normalized)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn validate_coupon_params(
        discount_type: DiscountType,
        discount_value: i64,
        max_discount_paise: Option<i64>,
        min_order_paise: i64,
        max_uses: Option<i32>,
        max_uses_per_user: i32,
        starts_at_epoch_ms: i64,
        ends_at_epoch_ms: i64,
    ) -> Result<(), DomainError> {
        if discount_value <= 0 {
            return Err(DomainError::Validation {
                code: "INVALID_DISCOUNT_VALUE",
                message: "discount_value must be greater than 0.".to_owned(),
            });
        }
        if discount_type == DiscountType::PercentBps && discount_value > 10_000 {
            return Err(DomainError::Validation {
                code: "INVALID_PERCENT_BPS",
                message: "PERCENT_BPS discount_value cannot exceed 10000 (100%).".to_owned(),
            });
        }
        if let Some(max_disc) = max_discount_paise {
            if max_disc <= 0 {
                return Err(DomainError::Validation {
                    code: "INVALID_MAX_DISCOUNT",
                    message: "max_discount_paise must be greater than 0.".to_owned(),
                });
            }
        }
        if min_order_paise < 0 {
            return Err(DomainError::Validation {
                code: "INVALID_MIN_ORDER",
                message: "min_order_paise must be non-negative.".to_owned(),
            });
        }
        if let Some(max_uses_val) = max_uses {
            if max_uses_val <= 0 {
                return Err(DomainError::Validation {
                    code: "INVALID_MAX_USES",
                    message: "max_uses must be greater than 0 when set.".to_owned(),
                });
            }
        }
        if max_uses_per_user < 1 {
            return Err(DomainError::Validation {
                code: "INVALID_MAX_USES_PER_USER",
                message: "max_uses_per_user must be at least 1.".to_owned(),
            });
        }
        if ends_at_epoch_ms <= starts_at_epoch_ms {
            return Err(DomainError::Validation {
                code: "INVALID_COUPON_WINDOW",
                message: "ends_at must be strictly after starts_at.".to_owned(),
            });
        }
        Ok(())
    }

    pub fn evaluate_eligibility(
        coupon: &CouponRuleInput,
        subtotal_paise: i64,
        now_epoch_ms: i64,
        use_conflict_on_limit: bool,
    ) -> Result<i64, DomainError> {
        if subtotal_paise < 0 {
            return Err(DomainError::NegativeAmount("subtotal_paise"));
        }
        if !coupon.is_active {
            return Err(DomainError::Unprocessable {
                code: "COUPON_INACTIVE",
                message: format!("Coupon '{}' is inactive.", coupon.code),
            });
        }
        if now_epoch_ms < coupon.starts_at_epoch_ms {
            return Err(DomainError::Unprocessable {
                code: "COUPON_NOT_STARTED",
                message: format!("Coupon '{}' is not active yet.", coupon.code),
            });
        }
        if now_epoch_ms >= coupon.ends_at_epoch_ms {
            return Err(DomainError::Unprocessable {
                code: "COUPON_EXPIRED",
                message: format!("Coupon '{}' has expired.", coupon.code),
            });
        }
        if subtotal_paise < coupon.min_order_paise {
            return Err(DomainError::Unprocessable {
                code: "COUPON_MIN_ORDER_NOT_MET",
                message: format!(
                    "Minimum order of {} required for coupon '{}'.",
                    Money::format_inr(coupon.min_order_paise),
                    coupon.code
                ),
            });
        }

        let total_global_used = i64::from(coupon.usage_count)
            .checked_add(i64::from(coupon.active_global_reservations))
            .ok_or(DomainError::ArithmeticOverflow("total_global_used"))?;
        if let Some(max_uses) = coupon.max_uses {
            if total_global_used >= i64::from(max_uses) {
                let message = format!(
                    "Coupon '{}' has reached its maximum usage limit.",
                    coupon.code
                );
                return Err(if use_conflict_on_limit {
                    DomainError::Conflict {
                        code: "COUPON_USAGE_LIMIT_REACHED",
                        message,
                    }
                } else {
                    DomainError::Unprocessable {
                        code: "COUPON_USAGE_LIMIT_REACHED",
                        message,
                    }
                });
            }
        }

        if coupon.user_used_count >= coupon.max_uses_per_user {
            let message = format!(
                "You have already used coupon '{}' the maximum allowed times.",
                coupon.code
            );
            return Err(if use_conflict_on_limit {
                DomainError::Conflict {
                    code: "COUPON_USER_LIMIT_REACHED",
                    message,
                }
            } else {
                DomainError::Unprocessable {
                    code: "COUPON_USER_LIMIT_REACHED",
                    message,
                }
            });
        }

        let spec = CouponCalculationSpec {
            discount_type: coupon.discount_type,
            discount_value: coupon.discount_value,
            max_discount_paise: coupon.max_discount_paise,
            min_order_paise: coupon.min_order_paise,
            is_active: coupon.is_active,
        };
        PricingEngine::calculate_discount_paise(subtotal_paise, Some(&spec))
    }

    pub fn evaluate_listing(
        coupon: &CouponRuleInput,
        subtotal_paise: Option<i64>,
    ) -> Result<CouponListingEvaluation, DomainError> {
        if let Some(sub) = subtotal_paise {
            if sub < 0 {
                return Err(DomainError::Validation {
                    code: "INVALID_SUBTOTAL",
                    message: "subtotal_paise must be non-negative.".to_owned(),
                });
            }
        }

        let used_global =
            i64::from(coupon.usage_count) + i64::from(coupon.active_global_reservations);
        let remaining_global = coupon
            .max_uses
            .map(|max| (i64::from(max) - used_global).max(0) as i32);
        let remaining_user = (coupon.max_uses_per_user - coupon.user_used_count).max(0);

        let ineligible_reason = if remaining_global.is_some_and(|rem| rem <= 0) {
            Some("Coupon redemption limit reached.".to_owned())
        } else if remaining_user <= 0 {
            Some("Per-user usage limit reached.".to_owned())
        } else {
            subtotal_paise
                .filter(|s| *s < coupon.min_order_paise)
                .map(|sub| {
                    format!(
                        "Add {} more (min order {}).",
                        Money::format_inr(coupon.min_order_paise - sub),
                        Money::format_inr(coupon.min_order_paise)
                    )
                })
        };

        let is_eligible = ineligible_reason.is_none();
        let estimated_discount_paise = match subtotal_paise {
            Some(sub) if is_eligible => {
                let spec = CouponCalculationSpec {
                    discount_type: coupon.discount_type,
                    discount_value: coupon.discount_value,
                    max_discount_paise: coupon.max_discount_paise,
                    min_order_paise: coupon.min_order_paise,
                    is_active: coupon.is_active,
                };
                PricingEngine::calculate_discount_paise(sub, Some(&spec))?
            }
            _ => 0,
        };

        Ok(CouponListingEvaluation {
            remaining_global_uses: remaining_global,
            remaining_user_uses: remaining_user,
            is_eligible,
            ineligible_reason,
            estimated_discount_paise,
        })
    }
}
