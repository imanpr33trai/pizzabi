use std::collections::HashSet;

use crate::cart_validation::{
    parse_uuid, size_code_str, CartItemCustomizationSummary, CartValidationIssue,
    CartValidationIssueCode, CatalogCheeseRef, CatalogProductRef, CatalogRefLookup,
    CatalogSauceRef, CatalogSizeRuleRef, CatalogVariantRef, EvaluatedCartItem,
    EvaluatedToppingView, OrderItemCustomizationSnapshot, OrderItemToppingSnapshot, ParsedCartItem,
    PizzaPriceBreakdown, SizeCode, ToppingPlacement,
};
use crate::error::DomainError;
use crate::money::Money;
use crate::pricing::PricingEngine;

pub const HALF_PRICE_BPS: i32 = 5_000;

pub fn default_size_rule(size_code: SizeCode) -> CatalogSizeRuleRef {
    match size_code {
        SizeCode::Regular => CatalogSizeRuleRef {
            size_code,
            display_name: "Regular".to_owned(),
            max_toppings: 5,
            max_extra_toppings: 3,
            supports_half_and_half: false,
            ingredient_multiplier_bps: 7_500,
            is_active: true,
        },
        SizeCode::Medium => CatalogSizeRuleRef {
            size_code,
            display_name: "Medium".to_owned(),
            max_toppings: 7,
            max_extra_toppings: 4,
            supports_half_and_half: true,
            ingredient_multiplier_bps: 10_000,
            is_active: true,
        },
        SizeCode::Large => CatalogSizeRuleRef {
            size_code,
            display_name: "Large".to_owned(),
            max_toppings: 9,
            max_extra_toppings: 5,
            supports_half_and_half: true,
            ingredient_multiplier_bps: 13_500,
            is_active: true,
        },
        SizeCode::ExtraLarge => CatalogSizeRuleRef {
            size_code,
            display_name: "Extra Large".to_owned(),
            max_toppings: 11,
            max_extra_toppings: 6,
            supports_half_and_half: true,
            ingredient_multiplier_bps: 17_000,
            is_active: true,
        },
    }
}

pub fn compute_configuration_signature(
    item: &ParsedCartItem,
    lookup: Option<&CatalogRefLookup>,
) -> String {
    let left_product = lookup.and_then(|l| l.products.get(&item.product_id));
    let right_product = lookup.and_then(|l| {
        item.right_product_id
            .as_ref()
            .and_then(|rid| l.products.get(rid))
    });

    let eff_cheese = item
        .cheese_id
        .or_else(|| left_product.and_then(|p| p.default_cheese_id))
        .map(|u| u.to_string())
        .unwrap_or_default();

    let eff_sauce = item
        .sauce_id
        .or_else(|| left_product.and_then(|p| p.default_sauce_id))
        .map(|u| u.to_string())
        .unwrap_or_default();

    let eff_right_prod = if item.is_half_and_half {
        item.right_product_id
            .map(|u| u.to_string())
            .unwrap_or_default()
    } else {
        String::new()
    };
    let eff_right_var = if item.is_half_and_half {
        item.right_variant_id
            .map(|u| u.to_string())
            .unwrap_or_default()
    } else {
        String::new()
    };
    let eff_right_sauce = if item.is_half_and_half {
        item.right_sauce_id
            .or_else(|| right_product.and_then(|p| p.default_sauce_id))
            .map(|u| u.to_string())
            .unwrap_or_else(|| eff_sauce.clone())
    } else {
        String::new()
    };

    let mut removed: Vec<String> = item
        .removed_included_topping_ids
        .iter()
        .map(ToString::to_string)
        .collect();
    removed.sort();
    removed.dedup();
    let removed_csv = removed.join(",");

    let mut sels = item.topping_selections.clone();
    sels.sort_by(|a, b| {
        a.topping_id
            .to_string()
            .cmp(&b.topping_id.to_string())
            .then_with(|| a.placement.as_str().cmp(b.placement.as_str()))
    });
    let toppings_csv = sels
        .iter()
        .map(|s| format!("{}:{}:{}", s.topping_id, s.placement.as_str(), s.quantity))
        .collect::<Vec<_>>()
        .join(",");

    format!(
        "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
        item.product_id,
        item.variant_id,
        item.crust_id,
        eff_cheese,
        item.extra_cheese,
        eff_sauce,
        item.extra_sauce,
        item.is_half_and_half,
        eff_right_prod,
        eff_right_var,
        eff_right_sauce,
        removed_csv,
        toppings_csv
    )
}

pub fn evaluate_single_item(
    idx: usize,
    input: &ParsedCartItem,
    lookup: &CatalogRefLookup,
) -> Result<EvaluatedCartItem, DomainError> {
    let mut issues = Vec::new();
    let prod = lookup.products.get(&input.product_id);
    let variant = lookup.variants.get(&input.variant_id);
    let crust = lookup.crusts.get(&input.crust_id);

    if !prod.is_some_and(|p| p.is_effective_active) {
        let prod_name = prod.map_or("Pizza", |p| p.name.as_str());
        issues.push(CartValidationIssue {
            code: CartValidationIssueCode::ProductUnavailable,
            message: format!("{prod_name} is currently unavailable."),
        });
    }

    match variant {
        None => issues.push(CartValidationIssue {
            code: CartValidationIssueCode::VariantUnavailable,
            message: "Selected size is currently unavailable.".to_owned(),
        }),
        Some(v) if !v.is_active => issues.push(CartValidationIssue {
            code: CartValidationIssueCode::VariantUnavailable,
            message: format!(
                "Selected size {} is currently unavailable.",
                size_code_str(v.size_code)
            ),
        }),
        Some(v) if v.product_id != input.product_id => issues.push(CartValidationIssue {
            code: CartValidationIssueCode::VariantProductMismatch,
            message: "Selected size does not belong to this pizza.".to_owned(),
        }),
        Some(_) => {}
    }

    let size_rule: Option<CatalogSizeRuleRef> = variant.map(|v| {
        if lookup.size_rules.is_empty() {
            default_size_rule(v.size_code)
        } else {
            match lookup.size_rules.get(&v.size_code) {
                Some(rule) => {
                    if !rule.is_active {
                        issues.push(CartValidationIssue {
                            code: CartValidationIssueCode::SizeUnavailable,
                            message: format!(
                                "Size {} is currently unavailable.",
                                rule.display_name
                            ),
                        });
                    }
                    rule.clone()
                }
                None => {
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::SizeUnavailable,
                        message: format!(
                            "Size {} is currently unavailable.",
                            size_code_str(v.size_code)
                        ),
                    });
                    default_size_rule(v.size_code)
                }
            }
        }
    });

    if !crust.is_some_and(|c| c.is_active) {
        let crust_name = crust.map_or("Selected crust", |c| c.name.as_str());
        issues.push(CartValidationIssue {
            code: CartValidationIssueCode::CrustUnavailable,
            message: format!("{crust_name} is currently unavailable."),
        });
    }

    let eff_cheese_id = input
        .cheese_id
        .or_else(|| prod.and_then(|p| p.default_cheese_id));
    let cheese: Option<&CatalogCheeseRef> = eff_cheese_id.and_then(|id| lookup.cheeses.get(&id));
    if eff_cheese_id.is_some() {
        match cheese {
            None => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::CheeseUnavailable,
                message: "Selected cheese is unavailable.".to_owned(),
            }),
            Some(c) if !c.is_active => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::CheeseUnavailable,
                message: format!("{} is currently unavailable.", c.name),
            }),
            Some(c) if input.extra_cheese && !c.supports_extra => {
                issues.push(CartValidationIssue {
                    code: CartValidationIssueCode::ExtraCheeseUnavailable,
                    message: format!("Extra cheese is not supported for {}.", c.name),
                })
            }
            Some(_) => {}
        }
    } else if input.extra_cheese {
        issues.push(CartValidationIssue {
            code: CartValidationIssueCode::ExtraCheeseUnavailable,
            message: "Extra cheese requires an active cheese selection.".to_owned(),
        });
    }

    let eff_sauce_id = input
        .sauce_id
        .or_else(|| prod.and_then(|p| p.default_sauce_id));
    let left_sauce: Option<&CatalogSauceRef> = eff_sauce_id.and_then(|id| lookup.sauces.get(&id));
    if eff_sauce_id.is_some() {
        match left_sauce {
            None => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::SauceUnavailable,
                message: "Selected sauce is unavailable.".to_owned(),
            }),
            Some(s) if !s.is_active => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::SauceUnavailable,
                message: format!("{} is currently unavailable.", s.name),
            }),
            Some(s) if input.extra_sauce && !s.supports_extra => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ExtraSauceUnavailable,
                message: format!("Extra sauce is not supported for {}.", s.name),
            }),
            Some(_) => {}
        }
    } else if input.extra_sauce {
        issues.push(CartValidationIssue {
            code: CartValidationIssueCode::ExtraSauceUnavailable,
            message: "Extra sauce requires an active sauce selection.".to_owned(),
        });
    }

    let mut right_product: Option<&CatalogProductRef> = None;
    let mut right_variant: Option<&CatalogVariantRef> = None;
    let mut eff_right_sauce_id = None;
    let mut right_sauce: Option<&CatalogSauceRef> = None;

    if input.is_half_and_half {
        if prod.is_some_and(|p| !p.supports_half_and_half) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::HalfAndHalfUnsupported,
                message: format!(
                    "{} cannot be customized as half-and-half.",
                    prod.map_or("Pizza", |p| p.name.as_str())
                ),
            });
        }
        if let Some(ref sr) = size_rule {
            if !sr.supports_half_and_half {
                issues.push(CartValidationIssue {
                    code: CartValidationIssueCode::HalfAndHalfUnsupported,
                    message: format!("Half-and-half is not available for {}.", sr.display_name),
                });
            }
        }
        if crust.is_some_and(|c| !c.supports_half_and_half) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::HalfAndHalfUnsupported,
                message: format!(
                    "Half-and-half is not supported with {} crust.",
                    crust.map_or("selected", |c| c.name.as_str())
                ),
            });
        }

        right_product = input
            .right_product_id
            .and_then(|id| lookup.products.get(&id));
        match right_product {
            None => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::RightHalfInvalid,
                message: "Right-half pizza selection is missing or unavailable.".to_owned(),
            }),
            Some(rp) if !rp.is_effective_active => issues.push(CartValidationIssue {
                code: CartValidationIssueCode::RightHalfInvalid,
                message: format!("Right-half pizza {} is currently unavailable.", rp.name),
            }),
            Some(rp) => {
                if !rp.supports_half_and_half {
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::HalfAndHalfUnsupported,
                        message: format!("{} cannot be used on a half-and-half pizza.", rp.name),
                    });
                }
                right_variant = input
                    .right_variant_id
                    .and_then(|vid| lookup.variants.get(&vid));
                match right_variant {
                    Some(rv) if rv.product_id == rp.id && rv.is_active => {
                        if let Some(lv) = variant {
                            if rv.size_code != lv.size_code {
                                issues.push(CartValidationIssue {
                                    code: CartValidationIssueCode::RightHalfInvalid,
                                    message: "Both halves must share the same pizza size."
                                        .to_owned(),
                                });
                            }
                        }
                    }
                    _ => issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::RightHalfInvalid,
                        message: "Right-half size variant is missing or unavailable.".to_owned(),
                    }),
                }
            }
        }

        eff_right_sauce_id = input
            .right_sauce_id
            .or_else(|| right_product.and_then(|p| p.default_sauce_id))
            .or(eff_sauce_id);
        right_sauce = eff_right_sauce_id.and_then(|id| lookup.sauces.get(&id));
        if eff_right_sauce_id.is_some() {
            match right_sauce {
                None => issues.push(CartValidationIssue {
                    code: CartValidationIssueCode::SauceUnavailable,
                    message: "Selected right-half sauce is unavailable.".to_owned(),
                }),
                Some(s) if !s.is_active => issues.push(CartValidationIssue {
                    code: CartValidationIssueCode::SauceUnavailable,
                    message: format!("{} is currently unavailable.", s.name),
                }),
                Some(s) if input.extra_sauce && !s.supports_extra => {
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::ExtraSauceUnavailable,
                        message: format!("Extra sauce is not supported for {}.", s.name),
                    })
                }
                Some(_) => {}
            }
        }
    }

    let mut allowed_included_ids = HashSet::new();
    if let Some(lp) = prod {
        for tid in &lp.included_topping_ids {
            allowed_included_ids.insert(*tid);
        }
    }
    if input.is_half_and_half {
        if let Some(rp) = right_product {
            for tid in &rp.included_topping_ids {
                allowed_included_ids.insert(*tid);
            }
        }
    }

    let mut seen_removed = HashSet::new();
    let mut removed_names = Vec::new();
    for rid in &input.removed_included_topping_ids {
        if !seen_removed.insert(*rid) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::DuplicateSelection,
                message: "Duplicate removed included topping selection.".to_owned(),
            });
            continue;
        }
        if !allowed_included_ids.contains(rid) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::IncludedToppingInvalid,
                message: "Topping cannot be removed because it is not included on this pizza."
                    .to_owned(),
            });
        } else if let Some(t) = lookup.toppings.get(rid) {
            removed_names.push(t.name.clone());
        }
    }

    let mut seen_topping_pairs = HashSet::new();
    let mut topping_views = Vec::with_capacity(input.topping_selections.len());
    let mut left_load: i32 = 0;
    let mut right_load: i32 = 0;
    let mut extra_half_weight_units: i32 = 0;
    let mut toppings_price: i64 = 0;

    for sel in &input.topping_selections {
        if !seen_topping_pairs.insert((sel.topping_id, sel.placement)) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::DuplicateSelection,
                message: format!(
                    "Duplicate topping selection for {}.",
                    sel.placement.as_str()
                ),
            });
            continue;
        }

        let row = lookup.toppings.get(&sel.topping_id);
        let available = row.is_some_and(|t| t.is_active);
        if !available {
            let t_name = row.map_or("Selected topping", |t| t.name.as_str());
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ToppingUnavailable,
                message: format!("{t_name} is no longer available."),
            });
        }

        if !input.is_half_and_half && sel.placement != ToppingPlacement::Whole {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ToppingPlacementInvalid,
                message: "Half-pizza topping placement requires half-and-half mode.".to_owned(),
            });
        } else if sel.placement != ToppingPlacement::Whole
            && row.is_some_and(|t| !t.supports_half_and_half)
        {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ToppingPlacementInvalid,
                message: format!(
                    "{} cannot be applied to a single half.",
                    row.map_or("Topping", |t| t.name.as_str())
                ),
            });
        }

        let max_per_qty = row.map_or(2, |t| t.max_quantity);
        if sel.quantity < 1 || sel.quantity > max_per_qty {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::InvalidQuantity,
                message: format!(
                    "{} quantity must be between 1 and {}.",
                    row.map_or("Topping", |t| t.name.as_str()),
                    max_per_qty
                ),
            });
        }

        let included_on_left =
            prod.is_some_and(|p| p.included_topping_ids.contains(&sel.topping_id));
        let included_on_right = if input.is_half_and_half {
            right_product.is_some_and(|p| p.included_topping_ids.contains(&sel.topping_id))
        } else {
            included_on_left
        };
        let not_removed = !seen_removed.contains(&sel.topping_id);
        let is_included = not_removed
            && match sel.placement {
                ToppingPlacement::Whole => {
                    included_on_left && (!input.is_half_and_half || included_on_right)
                }
                ToppingPlacement::Left => included_on_left,
                ToppingPlacement::Right => input.is_half_and_half && included_on_right,
            };

        let clamped_qty = sel.quantity.max(0);
        match sel.placement {
            ToppingPlacement::Whole => {
                left_load = left_load.saturating_add(clamped_qty);
                right_load = right_load.saturating_add(clamped_qty);
            }
            ToppingPlacement::Left => left_load = left_load.saturating_add(clamped_qty),
            ToppingPlacement::Right => right_load = right_load.saturating_add(clamped_qty),
        }

        let billable_qty = if is_included {
            (clamped_qty - 1).max(0)
        } else {
            clamped_qty
        };
        extra_half_weight_units = extra_half_weight_units.saturating_add(match sel.placement {
            ToppingPlacement::Whole => billable_qty.saturating_mul(2),
            ToppingPlacement::Left | ToppingPlacement::Right => billable_qty,
        });

        let unit_rate = match row {
            None => 0,
            Some(t) => match sel.placement {
                ToppingPlacement::Whole => t.extra_price_paise,
                ToppingPlacement::Left | ToppingPlacement::Right => {
                    Money::bps_of(t.extra_price_paise, HALF_PRICE_BPS)?
                }
            },
        };
        let total_price_paise = unit_rate
            .checked_mul(i64::from(billable_qty))
            .ok_or(DomainError::ArithmeticOverflow("topping_total_price"))?;
        toppings_price = toppings_price
            .checked_add(total_price_paise)
            .ok_or(DomainError::ArithmeticOverflow("toppings_price"))?;

        topping_views.push(EvaluatedToppingView {
            topping_id: sel.topping_id.to_string(),
            name: row.map_or_else(|| "Unavailable Topping".to_owned(), |t| t.name.clone()),
            is_veg: row.is_none_or(|t| t.is_veg),
            extra_price_paise: row.map_or(0, |t| t.extra_price_paise),
            is_available: available,
            placement: sel.placement,
            quantity: sel.quantity,
            is_included,
            unit_price_paise: unit_rate,
            total_price_paise,
        });
    }

    let effective_max_toppings = [
        size_rule.as_ref().map(|r| r.max_toppings),
        prod.and_then(|p| p.max_toppings_override),
        if input.is_half_and_half {
            right_product.and_then(|p| p.max_toppings_override)
        } else {
            None
        },
    ]
    .into_iter()
    .flatten()
    .min();

    if let Some(max_top) = effective_max_toppings {
        if left_load.max(right_load) > max_top {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ToppingLimitExceeded,
                message: format!("Maximum {max_top} toppings allowed for this pizza size."),
            });
        }
    }

    if let Some(ref sr) = size_rule {
        if extra_half_weight_units > sr.max_extra_toppings.saturating_mul(2) {
            issues.push(CartValidationIssue {
                code: CartValidationIssueCode::ExtraToppingLimitExceeded,
                message: format!(
                    "Maximum {} extra toppings allowed for {}.",
                    sr.max_extra_toppings, sr.display_name
                ),
            });
        }
    }

    let base_price_paise = variant.map_or(0, |v| v.price_paise);
    let half_and_half_surcharge_paise = if input.is_half_and_half {
        right_variant.map_or(0, |rv| (rv.price_paise - base_price_paise).max(0))
    } else {
        0
    };
    let crust_price_paise = crust.map_or(0, |c| c.extra_price_paise);
    let cheese_price_paise = cheese.map_or(0, |c| c.extra_price_paise);
    let extra_cheese_price_paise = if input.extra_cheese {
        cheese
            .filter(|c| c.supports_extra)
            .map_or(0, |c| c.extra_cheese_price_paise)
    } else {
        0
    };

    let sauce_price_paise = if !input.is_half_and_half || eff_sauce_id == eff_right_sauce_id {
        left_sauce.map_or(0, |s| s.extra_price_paise)
    } else {
        let lp = left_sauce
            .map(|s| Money::bps_of(s.extra_price_paise, HALF_PRICE_BPS))
            .transpose()?
            .unwrap_or(0);
        let rp = right_sauce
            .map(|s| Money::bps_of(s.extra_price_paise, HALF_PRICE_BPS))
            .transpose()?
            .unwrap_or(0);
        lp.checked_add(rp)
            .ok_or(DomainError::ArithmeticOverflow("sauce_price_paise"))?
    };

    let extra_sauce_price_paise = if !input.extra_sauce {
        0
    } else if !input.is_half_and_half || eff_sauce_id == eff_right_sauce_id {
        left_sauce
            .filter(|s| s.supports_extra)
            .map_or(0, |s| s.extra_sauce_price_paise)
    } else {
        let lp = left_sauce
            .filter(|s| s.supports_extra)
            .map(|s| Money::bps_of(s.extra_sauce_price_paise, HALF_PRICE_BPS))
            .transpose()?
            .unwrap_or(0);
        let rp = right_sauce
            .filter(|s| s.supports_extra)
            .map(|s| Money::bps_of(s.extra_sauce_price_paise, HALF_PRICE_BPS))
            .transpose()?
            .unwrap_or(0);
        lp.checked_add(rp)
            .ok_or(DomainError::ArithmeticOverflow("extra_sauce_price_paise"))?
    };

    let unit_price = base_price_paise
        .checked_add(half_and_half_surcharge_paise)
        .and_then(|v| v.checked_add(crust_price_paise))
        .and_then(|v| v.checked_add(cheese_price_paise))
        .and_then(|v| v.checked_add(extra_cheese_price_paise))
        .and_then(|v| v.checked_add(sauce_price_paise))
        .and_then(|v| v.checked_add(extra_sauce_price_paise))
        .and_then(|v| v.checked_add(toppings_price))
        .ok_or(DomainError::ArithmeticOverflow("cart_unit_price"))?;

    let line_total = PricingEngine::calculate_line_total_paise(unit_price, input.quantity)?;
    let signature = compute_configuration_signature(input, Some(lookup));

    let price_breakdown = PizzaPriceBreakdown {
        base_price_paise,
        half_and_half_surcharge_paise,
        crust_price_paise,
        cheese_price_paise,
        extra_cheese_price_paise,
        sauce_price_paise,
        extra_sauce_price_paise,
        toppings_price_paise: toppings_price,
        unit_price_paise: unit_price,
        line_total_paise: line_total,
    };

    let customization = CartItemCustomizationSummary {
        is_half_and_half: input.is_half_and_half,
        right_product_id: if input.is_half_and_half {
            right_product
                .map(|p| p.id.to_string())
                .or_else(|| input.right_product_id.map(|u| u.to_string()))
        } else {
            None
        },
        right_product_name: if input.is_half_and_half {
            right_product.map(|p| p.name.clone())
        } else {
            None
        },
        right_variant_id: if input.is_half_and_half {
            right_variant
                .map(|v| v.id.to_string())
                .or_else(|| input.right_variant_id.map(|u| u.to_string()))
        } else {
            None
        },
        cheese_id: eff_cheese_id.map(|u| u.to_string()),
        cheese_name: cheese.map(|c| c.name.clone()),
        extra_cheese: input.extra_cheese,
        sauce_id: eff_sauce_id.map(|u| u.to_string()),
        sauce_name: left_sauce.map(|s| s.name.clone()),
        right_sauce_id: if input.is_half_and_half {
            eff_right_sauce_id.map(|u| u.to_string())
        } else {
            None
        },
        right_sauce_name: if input.is_half_and_half {
            right_sauce.map(|s| s.name.clone())
        } else {
            None
        },
        extra_sauce: input.extra_sauce,
        removed_included_toppings: removed_names.clone(),
        configuration_signature: signature.clone(),
    };

    let left_prod_name = prod.map_or_else(|| "Unavailable Pizza".to_owned(), |p| p.name.clone());
    let display_product_name = if input.is_half_and_half {
        if let Some(rp) = right_product {
            format!("Half {} + Half {}", left_prod_name, rp.name)
        } else {
            left_prod_name.clone()
        }
    } else {
        left_prod_name.clone()
    };

    let order_snapshot = OrderItemCustomizationSnapshot {
        is_half_and_half: input.is_half_and_half,
        left_product_name: left_prod_name,
        right_product_id: customization.right_product_id.clone(),
        right_product_name: customization.right_product_name.clone(),
        cheese_name: customization.cheese_name.clone(),
        extra_cheese: input.extra_cheese,
        sauce_name: customization.sauce_name.clone(),
        right_sauce_name: customization.right_sauce_name.clone(),
        extra_sauce: input.extra_sauce,
        removed_included_toppings: removed_names,
        topping_details: topping_views
            .iter()
            .map(|tv| OrderItemToppingSnapshot {
                topping_id: tv.topping_id.clone(),
                name: tv.name.clone(),
                placement: tv.placement,
                quantity: tv.quantity,
                is_included: tv.is_included,
                unit_price_paise: tv.unit_price_paise,
                total_price_paise: tv.total_price_paise,
            })
            .collect(),
        configuration_signature: signature,
    };

    let is_veg = prod.is_none_or(|p| p.is_veg)
        && (!input.is_half_and_half || right_product.is_none_or(|p| p.is_veg))
        && topping_views.iter().all(|t| t.is_veg);
    let is_taxable = prod.is_none_or(|p| p.is_taxable)
        || (input.is_half_and_half && right_product.is_some_and(|p| p.is_taxable));

    let is_available = issues.is_empty();
    let item_id = input
        .item_id
        .clone()
        .unwrap_or_else(|| format!("line-{idx}"));

    Ok(EvaluatedCartItem {
        item_id,
        product_id: input.product_id.to_string(),
        product_name: display_product_name,
        is_veg,
        is_taxable,
        image_url: prod.and_then(|p| p.image_url.clone()),
        variant_id: input.variant_id.to_string(),
        size_code: variant.map(|v| v.size_code),
        base_price_paise,
        crust_id: input.crust_id.to_string(),
        crust_name: crust.map_or_else(|| "Unavailable Crust".to_owned(), |c| c.name.clone()),
        crust_extra_price_paise: crust_price_paise,
        toppings: topping_views,
        quantity: input.quantity,
        unit_price_paise: unit_price,
        line_total_paise: line_total,
        is_available,
        issues,
        customization,
        price_breakdown,
        order_snapshot,
        effective_cheese_id: eff_cheese_id,
        effective_sauce_id: eff_sauce_id,
        effective_right_product_id: if input.is_half_and_half {
            right_product.map(|p| p.id).or(input.right_product_id)
        } else {
            None
        },
        effective_right_variant_id: if input.is_half_and_half {
            right_variant.map(|v| v.id).or(input.right_variant_id)
        } else {
            None
        },
        effective_right_sauce_id: eff_right_sauce_id,
    })
}

pub fn parse_optional_uuid(
    raw: Option<&str>,
    field: &'static str,
) -> Result<Option<uuid::Uuid>, DomainError> {
    match raw.map(str::trim).filter(|s| !s.is_empty()) {
        Some(v) => parse_uuid(v, field).map(Some),
        None => Ok(None),
    }
}

#[derive(Debug, Clone)]
pub struct RawCatalogProductRef {
    pub id: String,
    pub name: String,
    pub is_veg: bool,
    pub is_taxable: bool,
    pub is_effective_active: bool,
    pub image_url: Option<String>,
    pub is_customizable: bool,
    pub supports_half_and_half: bool,
    pub default_cheese_id: Option<String>,
    pub default_sauce_id: Option<String>,
    pub included_topping_ids: Vec<String>,
    pub max_toppings_override: Option<i32>,
}

#[derive(Debug, Clone)]
pub struct RawCatalogVariantRef {
    pub id: String,
    pub product_id: String,
    pub size_code: SizeCode,
    pub price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone)]
pub struct RawCatalogCrustRef {
    pub id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
    pub supports_half_and_half: bool,
}

#[derive(Debug, Clone)]
pub struct RawCatalogToppingRef {
    pub id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_active: bool,
    pub max_quantity: i32,
    pub supports_half_and_half: bool,
}

#[derive(Debug, Clone)]
pub struct RawCatalogCheeseRef {
    pub id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub extra_cheese_price_paise: i64,
    pub supports_extra: bool,
    pub is_vegan: bool,
    pub is_active: bool,
}

#[derive(Debug, Clone)]
pub struct RawCatalogSauceRef {
    pub id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub extra_sauce_price_paise: i64,
    pub supports_extra: bool,
    pub is_vegan: bool,
    pub is_active: bool,
}

#[derive(Debug, Clone, Default)]
pub struct RawCatalogLookupInput {
    pub products: Vec<RawCatalogProductRef>,
    pub variants: Vec<RawCatalogVariantRef>,
    pub crusts: Vec<RawCatalogCrustRef>,
    pub toppings: Vec<RawCatalogToppingRef>,
    pub size_rules: Vec<CatalogSizeRuleRef>,
    pub cheeses: Vec<RawCatalogCheeseRef>,
    pub sauces: Vec<RawCatalogSauceRef>,
}

impl RawCatalogLookupInput {
    pub fn into_lookup(self) -> Result<CatalogRefLookup, DomainError> {
        let mut lookup = CatalogRefLookup::default();
        for p in self.products {
            let id = parse_uuid(&p.id, "product_id")?;
            let default_cheese_id =
                parse_optional_uuid(p.default_cheese_id.as_deref(), "default_cheese_id")?;
            let default_sauce_id =
                parse_optional_uuid(p.default_sauce_id.as_deref(), "default_sauce_id")?;
            let mut included_topping_ids = Vec::with_capacity(p.included_topping_ids.len());
            for tid in p.included_topping_ids {
                included_topping_ids.push(parse_uuid(&tid, "included_topping_id")?);
            }
            lookup.products.insert(
                id,
                CatalogProductRef {
                    id,
                    name: p.name,
                    is_veg: p.is_veg,
                    is_taxable: p.is_taxable,
                    is_effective_active: p.is_effective_active,
                    image_url: p.image_url,
                    is_customizable: p.is_customizable,
                    supports_half_and_half: p.supports_half_and_half,
                    default_cheese_id,
                    default_sauce_id,
                    included_topping_ids,
                    max_toppings_override: p.max_toppings_override,
                },
            );
        }
        for v in self.variants {
            let id = parse_uuid(&v.id, "variant_id")?;
            let product_id = parse_uuid(&v.product_id, "product_id")?;
            lookup.variants.insert(
                id,
                CatalogVariantRef {
                    id,
                    product_id,
                    size_code: v.size_code,
                    price_paise: v.price_paise,
                    is_active: v.is_active,
                },
            );
        }
        for c in self.crusts {
            let id = parse_uuid(&c.id, "crust_id")?;
            lookup.crusts.insert(
                id,
                crate::cart_validation::CatalogCrustRef {
                    id,
                    name: c.name,
                    extra_price_paise: c.extra_price_paise,
                    is_active: c.is_active,
                    supports_half_and_half: c.supports_half_and_half,
                },
            );
        }
        for t in self.toppings {
            let id = parse_uuid(&t.id, "topping_id")?;
            lookup.toppings.insert(
                id,
                crate::cart_validation::CatalogToppingRef {
                    id,
                    name: t.name,
                    is_veg: t.is_veg,
                    extra_price_paise: t.extra_price_paise,
                    is_active: t.is_active,
                    max_quantity: t.max_quantity,
                    supports_half_and_half: t.supports_half_and_half,
                },
            );
        }
        for sr in self.size_rules {
            lookup.size_rules.insert(sr.size_code, sr);
        }
        for ch in self.cheeses {
            let id = parse_uuid(&ch.id, "cheese_id")?;
            lookup.cheeses.insert(
                id,
                CatalogCheeseRef {
                    id,
                    name: ch.name,
                    extra_price_paise: ch.extra_price_paise,
                    extra_cheese_price_paise: ch.extra_cheese_price_paise,
                    supports_extra: ch.supports_extra,
                    is_vegan: ch.is_vegan,
                    is_active: ch.is_active,
                },
            );
        }
        for s in self.sauces {
            let id = parse_uuid(&s.id, "sauce_id")?;
            lookup.sauces.insert(
                id,
                CatalogSauceRef {
                    id,
                    name: s.name,
                    extra_price_paise: s.extra_price_paise,
                    extra_sauce_price_paise: s.extra_sauce_price_paise,
                    supports_extra: s.supports_extra,
                    is_vegan: s.is_vegan,
                    is_active: s.is_active,
                },
            );
        }
        Ok(lookup)
    }
}

pub fn compute_item_signature_from_input(
    input: crate::cart_validation::CartItemInput,
    raw_lookup: Option<RawCatalogLookupInput>,
) -> Result<String, DomainError> {
    let parsed = crate::cart_validation::CartValidation::validate_and_parse_inputs(&[input])?;
    let lookup = raw_lookup
        .map(RawCatalogLookupInput::into_lookup)
        .transpose()?;
    Ok(compute_configuration_signature(&parsed[0], lookup.as_ref()))
}

pub fn evaluate_item_from_input(
    input: crate::cart_validation::CartItemInput,
    raw_lookup: RawCatalogLookupInput,
) -> Result<EvaluatedCartItem, DomainError> {
    let parsed = crate::cart_validation::CartValidation::validate_and_parse_inputs(&[input])?;
    let lookup = raw_lookup.into_lookup()?;
    evaluate_single_item(0, &parsed[0], &lookup)
}
