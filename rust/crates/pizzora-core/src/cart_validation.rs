use std::collections::{BTreeSet, HashMap, HashSet};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DomainError;
use crate::pizza_builder::{
    compute_configuration_signature, evaluate_single_item, parse_optional_uuid,
};

pub const MAX_CART_LINE_ITEMS: usize = 50;
pub const MAX_ITEM_QUANTITY: i32 = 25;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SizeCode {
    Regular,
    Medium,
    Large,
    ExtraLarge,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ToppingPlacement {
    #[default]
    Whole,
    Left,
    Right,
}

impl ToppingPlacement {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Whole => "WHOLE",
            Self::Left => "LEFT",
            Self::Right => "RIGHT",
        }
    }

    pub fn from_db_str(raw: &str) -> Result<Self, DomainError> {
        match raw.trim().to_ascii_uppercase().as_str() {
            "WHOLE" => Ok(Self::Whole),
            "LEFT" => Ok(Self::Left),
            "RIGHT" => Ok(Self::Right),
            other => Err(DomainError::Validation {
                code: "INVALID_TOPPING_PLACEMENT",
                message: format!("Unsupported topping placement '{other}'."),
            }),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CartValidationIssueCode {
    ProductUnavailable,
    VariantUnavailable,
    CrustUnavailable,
    ToppingUnavailable,
    InvalidQuantity,
    VariantProductMismatch,
    SizeUnavailable,
    CheeseUnavailable,
    SauceUnavailable,
    ExtraCheeseUnavailable,
    ExtraSauceUnavailable,
    HalfAndHalfUnsupported,
    RightHalfInvalid,
    ToppingPlacementInvalid,
    ToppingLimitExceeded,
    ExtraToppingLimitExceeded,
    DuplicateSelection,
    IncludedToppingInvalid,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartValidationIssue {
    pub code: CartValidationIssueCode,
    pub message: String,
}

fn default_one() -> i32 {
    1
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartToppingInput {
    pub topping_id: String,
    #[serde(default)]
    pub placement: ToppingPlacement,
    #[serde(default = "default_one")]
    pub quantity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct CartItemInput {
    #[serde(default)]
    pub id: Option<String>,
    pub product_id: String,
    pub variant_id: String,
    pub crust_id: String,
    #[serde(default)]
    pub topping_ids: Vec<String>,
    pub quantity: i32,
    #[serde(default)]
    pub cheese_id: Option<String>,
    #[serde(default)]
    pub extra_cheese: bool,
    #[serde(default)]
    pub sauce_id: Option<String>,
    #[serde(default)]
    pub extra_sauce: bool,
    #[serde(default)]
    pub is_half_and_half: bool,
    #[serde(default)]
    pub right_product_id: Option<String>,
    #[serde(default)]
    pub right_variant_id: Option<String>,
    #[serde(default)]
    pub right_sauce_id: Option<String>,
    #[serde(default)]
    pub removed_included_topping_ids: Vec<String>,
    #[serde(default)]
    pub topping_selections: Vec<CartToppingInput>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ParsedCartToppingSelection {
    pub topping_id: Uuid,
    pub placement: ToppingPlacement,
    pub quantity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedCartItem {
    pub item_id: Option<String>,
    pub product_id: Uuid,
    pub variant_id: Uuid,
    pub crust_id: Uuid,
    pub topping_ids: Vec<Uuid>,
    pub quantity: i32,
    pub cheese_id: Option<Uuid>,
    pub extra_cheese: bool,
    pub sauce_id: Option<Uuid>,
    pub extra_sauce: bool,
    pub is_half_and_half: bool,
    pub right_product_id: Option<Uuid>,
    pub right_variant_id: Option<Uuid>,
    pub right_sauce_id: Option<Uuid>,
    pub removed_included_topping_ids: Vec<Uuid>,
    pub topping_selections: Vec<ParsedCartToppingSelection>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogSizeRuleRef {
    pub size_code: SizeCode,
    pub display_name: String,
    pub max_toppings: i32,
    pub max_extra_toppings: i32,
    pub supports_half_and_half: bool,
    pub ingredient_multiplier_bps: i32,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogCheeseRef {
    pub id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub extra_cheese_price_paise: i64,
    pub supports_extra: bool,
    pub is_vegan: bool,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogSauceRef {
    pub id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub extra_sauce_price_paise: i64,
    pub supports_extra: bool,
    pub is_vegan: bool,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogProductRef {
    pub id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub is_taxable: bool,
    pub is_effective_active: bool,
    pub image_url: Option<String>,
    #[serde(default = "default_true_flag")]
    pub is_customizable: bool,
    #[serde(default = "default_true_flag")]
    pub supports_half_and_half: bool,
    #[serde(default)]
    pub default_cheese_id: Option<Uuid>,
    #[serde(default)]
    pub default_sauce_id: Option<Uuid>,
    #[serde(default)]
    pub included_topping_ids: Vec<Uuid>,
    #[serde(default)]
    pub max_toppings_override: Option<i32>,
}

fn default_true_flag() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogVariantRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub size_code: SizeCode,
    pub price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogCrustRef {
    pub id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
    #[serde(default = "default_true_flag")]
    pub supports_half_and_half: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogToppingRef {
    pub id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_active: bool,
    #[serde(default = "default_two")]
    pub max_quantity: i32,
    #[serde(default = "default_true_flag")]
    pub supports_half_and_half: bool,
}

fn default_two() -> i32 {
    2
}

#[derive(Debug, Clone, Default)]
pub struct CatalogRefLookup {
    pub products: HashMap<Uuid, CatalogProductRef>,
    pub variants: HashMap<Uuid, CatalogVariantRef>,
    pub crusts: HashMap<Uuid, CatalogCrustRef>,
    pub toppings: HashMap<Uuid, CatalogToppingRef>,
    pub size_rules: HashMap<SizeCode, CatalogSizeRuleRef>,
    pub cheeses: HashMap<Uuid, CatalogCheeseRef>,
    pub sauces: HashMap<Uuid, CatalogSauceRef>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct PizzaPriceBreakdown {
    pub base_price_paise: i64,
    pub half_and_half_surcharge_paise: i64,
    pub crust_price_paise: i64,
    pub cheese_price_paise: i64,
    pub extra_cheese_price_paise: i64,
    pub sauce_price_paise: i64,
    pub extra_sauce_price_paise: i64,
    pub toppings_price_paise: i64,
    pub unit_price_paise: i64,
    pub line_total_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct CartItemCustomizationSummary {
    pub is_half_and_half: bool,
    #[serde(default)]
    pub right_product_id: Option<String>,
    #[serde(default)]
    pub right_product_name: Option<String>,
    #[serde(default)]
    pub right_variant_id: Option<String>,
    #[serde(default)]
    pub cheese_id: Option<String>,
    #[serde(default)]
    pub cheese_name: Option<String>,
    pub extra_cheese: bool,
    #[serde(default)]
    pub sauce_id: Option<String>,
    #[serde(default)]
    pub sauce_name: Option<String>,
    #[serde(default)]
    pub right_sauce_id: Option<String>,
    #[serde(default)]
    pub right_sauce_name: Option<String>,
    pub extra_sauce: bool,
    #[serde(default)]
    pub removed_included_toppings: Vec<String>,
    #[serde(default)]
    pub configuration_signature: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderItemToppingSnapshot {
    pub topping_id: String,
    pub name: String,
    #[serde(default)]
    pub placement: ToppingPlacement,
    #[serde(default = "default_one")]
    pub quantity: i32,
    #[serde(default)]
    pub is_included: bool,
    #[serde(default)]
    pub unit_price_paise: i64,
    #[serde(default)]
    pub total_price_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct OrderItemCustomizationSnapshot {
    #[serde(default)]
    pub is_half_and_half: bool,
    #[serde(default)]
    pub left_product_name: String,
    #[serde(default)]
    pub right_product_id: Option<String>,
    #[serde(default)]
    pub right_product_name: Option<String>,
    #[serde(default)]
    pub cheese_name: Option<String>,
    #[serde(default)]
    pub extra_cheese: bool,
    #[serde(default)]
    pub sauce_name: Option<String>,
    #[serde(default)]
    pub right_sauce_name: Option<String>,
    #[serde(default)]
    pub extra_sauce: bool,
    #[serde(default)]
    pub removed_included_toppings: Vec<String>,
    #[serde(default)]
    pub topping_details: Vec<OrderItemToppingSnapshot>,
    #[serde(default)]
    pub configuration_signature: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedToppingView {
    pub topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_available: bool,
    pub placement: ToppingPlacement,
    pub quantity: i32,
    pub is_included: bool,
    pub unit_price_paise: i64,
    pub total_price_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedCartItem {
    pub item_id: String,
    pub product_id: String,
    pub product_name: String,
    pub is_veg: bool,
    pub is_taxable: bool,
    pub image_url: Option<String>,
    pub variant_id: String,
    pub size_code: Option<SizeCode>,
    pub base_price_paise: i64,
    pub crust_id: String,
    pub crust_name: String,
    pub crust_extra_price_paise: i64,
    pub toppings: Vec<EvaluatedToppingView>,
    pub quantity: i32,
    pub unit_price_paise: i64,
    pub line_total_paise: i64,
    pub is_available: bool,
    pub issues: Vec<CartValidationIssue>,
    pub customization: CartItemCustomizationSummary,
    pub price_breakdown: PizzaPriceBreakdown,
    pub order_snapshot: OrderItemCustomizationSnapshot,
    pub effective_cheese_id: Option<Uuid>,
    pub effective_sauce_id: Option<Uuid>,
    pub effective_right_product_id: Option<Uuid>,
    pub effective_right_variant_id: Option<Uuid>,
    pub effective_right_sauce_id: Option<Uuid>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedCartSummary {
    pub items: Vec<EvaluatedCartItem>,
    pub subtotal_paise: i64,
    pub total_item_count: i32,
    pub all_available: bool,
    pub unavailable_count: usize,
}

pub struct CartValidation;

impl CartValidation {
    pub fn validate_and_parse_inputs(
        items: &[CartItemInput],
    ) -> Result<Vec<ParsedCartItem>, DomainError> {
        if items.len() > MAX_CART_LINE_ITEMS {
            return Err(DomainError::Validation {
                code: "CART_LIMIT_EXCEEDED",
                message: format!(
                    "Cart cannot contain more than {MAX_CART_LINE_ITEMS} distinct line items."
                ),
            });
        }

        let mut parsed = Vec::with_capacity(items.len());
        for (index, raw) in items.iter().enumerate() {
            if !(1..=MAX_ITEM_QUANTITY).contains(&raw.quantity) {
                return Err(DomainError::Validation {
                    code: "INVALID_QUANTITY",
                    message: format!(
                        "Quantity for item #{index} must be between 1 and {MAX_ITEM_QUANTITY}."
                    ),
                });
            }

            let product_id = parse_uuid(&raw.product_id, "product_id")?;
            let variant_id = parse_uuid(&raw.variant_id, "variant_id")?;
            let crust_id = parse_uuid(&raw.crust_id, "crust_id")?;
            let cheese_id = parse_optional_uuid(raw.cheese_id.as_deref(), "cheese_id")?;
            let sauce_id = parse_optional_uuid(raw.sauce_id.as_deref(), "sauce_id")?;
            let right_product_id = if raw.is_half_and_half {
                parse_optional_uuid(raw.right_product_id.as_deref(), "right_product_id")?
            } else {
                None
            };
            let right_variant_id = if raw.is_half_and_half {
                parse_optional_uuid(raw.right_variant_id.as_deref(), "right_variant_id")?
            } else {
                None
            };
            let right_sauce_id = if raw.is_half_and_half {
                parse_optional_uuid(raw.right_sauce_id.as_deref(), "right_sauce_id")?
            } else {
                None
            };

            let mut removed_set = BTreeSet::new();
            for rid_raw in &raw.removed_included_topping_ids {
                removed_set.insert(parse_uuid(rid_raw, "removed_included_topping_id")?.to_string());
            }
            let removed_included_topping_ids = removed_set
                .into_iter()
                .map(|s| Uuid::parse_str(&s))
                .collect::<Result<Vec<_>, _>>()
                .map_err(|_| DomainError::Validation {
                    code: "INVALID_UUID",
                    message: "Invalid removed_included_topping_id UUID".to_owned(),
                })?;

            let mut topping_selections = Vec::new();
            let topping_ids = if !raw.topping_selections.is_empty() {
                let mut seen_pairs = HashSet::new();
                let mut unique_ids = BTreeSet::new();
                for sel in &raw.topping_selections {
                    if sel.quantity < 1 {
                        return Err(DomainError::Validation {
                            code: "INVALID_QUANTITY",
                            message: "Topping selection quantity must be at least 1.".to_owned(),
                        });
                    }
                    let tid = parse_uuid(&sel.topping_id, "topping_id")?;
                    if !seen_pairs.insert((tid, sel.placement)) {
                        return Err(DomainError::Validation {
                            code: "DUPLICATE_TOPPING_SELECTION",
                            message: format!(
                                "Duplicate topping selection {tid} for {}.",
                                sel.placement.as_str()
                            ),
                        });
                    }
                    unique_ids.insert(tid.to_string());
                    topping_selections.push(ParsedCartToppingSelection {
                        topping_id: tid,
                        placement: sel.placement,
                        quantity: sel.quantity,
                    });
                }
                topping_selections.sort_by(|a, b| {
                    a.topping_id
                        .to_string()
                        .cmp(&b.topping_id.to_string())
                        .then_with(|| a.placement.as_str().cmp(b.placement.as_str()))
                });
                unique_ids
                    .into_iter()
                    .map(|s| Uuid::parse_str(&s))
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|_| DomainError::Validation {
                        code: "INVALID_UUID",
                        message: "Invalid topping_id UUID".to_owned(),
                    })?
            } else {
                let mut sorted_toppings_set = BTreeSet::new();
                for tid_raw in &raw.topping_ids {
                    sorted_toppings_set.insert(parse_uuid(tid_raw, "topping_id")?.to_string());
                }
                let tids = sorted_toppings_set
                    .into_iter()
                    .map(|s| Uuid::parse_str(&s))
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|_| DomainError::Validation {
                        code: "INVALID_UUID",
                        message: "Invalid topping_id UUID".to_owned(),
                    })?;
                for tid in &tids {
                    topping_selections.push(ParsedCartToppingSelection {
                        topping_id: *tid,
                        placement: ToppingPlacement::Whole,
                        quantity: 1,
                    });
                }
                tids
            };

            let item_id = raw
                .id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned);

            parsed.push(ParsedCartItem {
                item_id,
                product_id,
                variant_id,
                crust_id,
                topping_ids,
                quantity: raw.quantity,
                cheese_id,
                extra_cheese: raw.extra_cheese,
                sauce_id,
                extra_sauce: raw.extra_sauce,
                is_half_and_half: raw.is_half_and_half,
                right_product_id,
                right_variant_id,
                right_sauce_id,
                removed_included_topping_ids,
                topping_selections,
            });
        }

        Ok(parsed)
    }

    pub fn merge_duplicate_configurations(items: Vec<ParsedCartItem>) -> Vec<ParsedCartItem> {
        Self::merge_duplicate_configurations_with_lookup(items, None)
    }

    pub fn merge_duplicate_configurations_with_lookup(
        items: Vec<ParsedCartItem>,
        lookup: Option<&CatalogRefLookup>,
    ) -> Vec<ParsedCartItem> {
        let mut index_by_sig: HashMap<String, usize> = HashMap::new();
        let mut merged: Vec<ParsedCartItem> = Vec::with_capacity(items.len());

        for item in items {
            let sig = compute_configuration_signature(&item, lookup);
            if let Some(&existing_idx) = index_by_sig.get(&sig) {
                let existing = &mut merged[existing_idx];
                existing.quantity = existing
                    .quantity
                    .saturating_add(item.quantity)
                    .min(MAX_ITEM_QUANTITY);
            } else {
                index_by_sig.insert(sig, merged.len());
                merged.push(item);
            }
        }

        merged
    }

    pub fn ensure_catalog_fk_references(
        items: &[ParsedCartItem],
        lookup: &CatalogRefLookup,
    ) -> Result<(), DomainError> {
        for item in items {
            if !lookup.products.contains_key(&item.product_id) {
                return Err(DomainError::Validation {
                    code: "INVALID_PRODUCT_ID",
                    message: format!("Product {} does not exist.", item.product_id),
                });
            }
            let variant_matches = lookup
                .variants
                .get(&item.variant_id)
                .is_some_and(|v| v.product_id == item.product_id);
            if !variant_matches {
                return Err(DomainError::Validation {
                    code: "VARIANT_PRODUCT_MISMATCH",
                    message: format!(
                        "Variant {} does not belong to product {}.",
                        item.variant_id, item.product_id
                    ),
                });
            }
            if !lookup.crusts.contains_key(&item.crust_id) {
                return Err(DomainError::Validation {
                    code: "INVALID_CRUST_ID",
                    message: format!("Crust {} does not exist.", item.crust_id),
                });
            }
            if let Some(cid) = item.cheese_id {
                if !lookup.cheeses.contains_key(&cid) {
                    return Err(DomainError::Validation {
                        code: "INVALID_CHEESE_ID",
                        message: format!("Cheese {cid} does not exist."),
                    });
                }
            }
            if let Some(sid) = item.sauce_id {
                if !lookup.sauces.contains_key(&sid) {
                    return Err(DomainError::Validation {
                        code: "INVALID_SAUCE_ID",
                        message: format!("Sauce {sid} does not exist."),
                    });
                }
            }
            if item.is_half_and_half {
                let rpid = item
                    .right_product_id
                    .ok_or_else(|| DomainError::Validation {
                        code: "RIGHT_HALF_INVALID",
                        message: "right_product_id is required when is_half_and_half is true."
                            .to_owned(),
                    })?;
                if !lookup.products.contains_key(&rpid) {
                    return Err(DomainError::Validation {
                        code: "INVALID_PRODUCT_ID",
                        message: format!("Right product {rpid} does not exist."),
                    });
                }
                let rvid = item
                    .right_variant_id
                    .ok_or_else(|| DomainError::Validation {
                        code: "RIGHT_HALF_INVALID",
                        message: "right_variant_id is required when is_half_and_half is true."
                            .to_owned(),
                    })?;
                let right_variant_matches = lookup
                    .variants
                    .get(&rvid)
                    .is_some_and(|v| v.product_id == rpid);
                if !right_variant_matches {
                    return Err(DomainError::Validation {
                        code: "VARIANT_PRODUCT_MISMATCH",
                        message: format!("Variant {rvid} does not belong to right product {rpid}."),
                    });
                }
                if let Some(rsid) = item.right_sauce_id {
                    if !lookup.sauces.contains_key(&rsid) {
                        return Err(DomainError::Validation {
                            code: "INVALID_SAUCE_ID",
                            message: format!("Right sauce {rsid} does not exist."),
                        });
                    }
                }
            }
            for rid in &item.removed_included_topping_ids {
                if !lookup.toppings.contains_key(rid) {
                    return Err(DomainError::Validation {
                        code: "INVALID_TOPPING_ID",
                        message: format!("Removed included topping {rid} does not exist."),
                    });
                }
            }
            for sel in &item.topping_selections {
                if !lookup.toppings.contains_key(&sel.topping_id) {
                    return Err(DomainError::Validation {
                        code: "INVALID_TOPPING_ID",
                        message: format!("Topping {} does not exist.", sel.topping_id),
                    });
                }
            }
        }
        Ok(())
    }

    pub fn evaluate_cart(
        items: &[ParsedCartItem],
        lookup: &CatalogRefLookup,
    ) -> Result<EvaluatedCartSummary, DomainError> {
        let mut evaluated_items = Vec::with_capacity(items.len());
        let mut subtotal_paise: i64 = 0;
        let mut total_item_count: i32 = 0;
        let mut unavailable_count: usize = 0;

        for (idx, input) in items.iter().enumerate() {
            let evaluated = evaluate_single_item(idx, input, lookup)?;
            if evaluated.is_available {
                subtotal_paise = subtotal_paise
                    .checked_add(evaluated.line_total_paise)
                    .ok_or(DomainError::ArithmeticOverflow("cart_subtotal"))?;
            } else {
                unavailable_count += 1;
            }
            total_item_count = total_item_count
                .checked_add(evaluated.quantity)
                .ok_or(DomainError::ArithmeticOverflow("cart_total_item_count"))?;
            evaluated_items.push(evaluated);
        }

        Ok(EvaluatedCartSummary {
            items: evaluated_items,
            subtotal_paise,
            total_item_count,
            all_available: unavailable_count == 0,
            unavailable_count,
        })
    }
}

pub(crate) fn parse_uuid(raw: &str, field: &'static str) -> Result<Uuid, DomainError> {
    Uuid::parse_str(raw.trim()).map_err(|_| DomainError::Validation {
        code: "INVALID_UUID",
        message: format!("Invalid {field} UUID: {raw}"),
    })
}

pub(crate) const fn size_code_str(size: SizeCode) -> &'static str {
    match size {
        SizeCode::Regular => "REGULAR",
        SizeCode::Medium => "MEDIUM",
        SizeCode::Large => "LARGE",
        SizeCode::ExtraLarge => "EXTRA_LARGE",
    }
}
