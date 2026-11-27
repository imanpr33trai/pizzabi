use std::collections::{BTreeSet, HashMap};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DomainError;
use crate::pricing::PricingEngine;

pub const MAX_CART_LINE_ITEMS: usize = 50;
pub const MAX_ITEM_QUANTITY: i32 = 25;

// Half-and-half limits per contract
pub const MAX_HALF_AND_HALF_TOPPINGS_PER_HALF: usize = 10;
pub const MAX_HALF_AND_HALF_DISTINCT_TOPPINGS: usize = 15;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SizeCode {
    Regular,
    Medium,
    Large,
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
    // Phase 8 modifier validation codes
    CheeseUnavailable,
    SauceUnavailable,
    StandardToppingUnavailable,
    HalfAndHalfUnavailable,
    HalfAndHalfToppingLimitExceeded,
    HalfAndHalfLeftRightOverlap,
    StandardToppingNotInProduct,
    CheeseNotInProduct,
    SauceNotInProduct,
    HalfAndHalfConfigNotInProduct,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartValidationIssue {
    pub code: CartValidationIssueCode,
    pub message: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartItemInput {
    pub id: Option<String>,
    pub product_id: String,
    pub variant_id: String,
    pub crust_id: String,
    pub topping_ids: Vec<String>,
    pub quantity: i32,
    // Phase 8 modifiers. `serde(default)` keeps legacy payloads deserializable;
    // `Default` lets old Rust struct literals use `..Default::default()`.
    #[serde(default)]
    pub cheese_id: Option<String>,
    #[serde(default)]
    pub sauce_id: Option<String>,
    #[serde(default)]
    pub standard_topping_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_config_id: Option<String>,
    #[serde(default)]
    pub half_and_half_left_topping_ids: Vec<String>,
    #[serde(default)]
    pub half_and_half_right_topping_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedCartItem {
    pub item_id: Option<String>,
    pub product_id: Uuid,
    pub variant_id: Uuid,
    pub crust_id: Uuid,
    pub topping_ids: Vec<Uuid>,
    pub quantity: i32,
    // Phase 8 modifiers
    pub cheese_id: Option<Uuid>,
    pub sauce_id: Option<Uuid>,
    pub standard_topping_ids: Vec<Uuid>,
    pub half_and_half_config_id: Option<Uuid>,
    pub half_and_half_left_topping_ids: Vec<Uuid>,
    pub half_and_half_right_topping_ids: Vec<Uuid>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct ConfigKey {
    product_id: Uuid,
    variant_id: Uuid,
    crust_id: Uuid,
    topping_ids: Vec<Uuid>,
    cheese_id: Option<Uuid>,
    sauce_id: Option<Uuid>,
    standard_topping_ids: Vec<Uuid>,
    half_and_half_config_id: Option<Uuid>,
    half_and_half_left_topping_ids: Vec<Uuid>,
    half_and_half_right_topping_ids: Vec<Uuid>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogProductRef {
    pub id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub is_taxable: bool,
    pub is_effective_active: bool,
    pub image_url: Option<String>,
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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogToppingRef {
    pub id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

/// Cheese option reference.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogCheeseRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

/// Sauce option reference.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogSauceRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

/// Standard topping reference.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogStandardToppingRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_active: bool,
}

/// Half-and-half configuration reference.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogHalfAndHalfConfigRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub left_topping_ids: Vec<Uuid>,
    pub right_topping_ids: Vec<Uuid>,
    pub half_and_half_surcharge_paise: i64,
    pub is_active: bool,
}

/// Product topping availability reference (product_toppings join table).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogProductToppingRef {
    pub product_id: Uuid,
    pub topping_id: Uuid,
}

#[derive(Debug, Clone, Default)]
pub struct CatalogRefLookup {
    pub products: HashMap<Uuid, CatalogProductRef>,
    pub variants: HashMap<Uuid, CatalogVariantRef>,
    pub crusts: HashMap<Uuid, CatalogCrustRef>,
    pub toppings: HashMap<Uuid, CatalogToppingRef>,
    // Phase 8 modifiers
    pub cheeses: HashMap<Uuid, CatalogCheeseRef>,
    pub sauces: HashMap<Uuid, CatalogSauceRef>,
    pub standard_toppings: HashMap<Uuid, CatalogStandardToppingRef>,
    pub half_and_half_configs: HashMap<Uuid, CatalogHalfAndHalfConfigRef>,
    pub product_toppings: HashMap<Uuid, Vec<CatalogProductToppingRef>>, // keyed by product_id
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedToppingView {
    pub topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

/// Evaluated cheese view.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedCheeseView {
    pub cheese_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

/// Evaluated sauce view.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedSauceView {
    pub sauce_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

/// Evaluated standard topping view.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedStandardToppingView {
    pub standard_topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_removed: bool,
    pub is_available: bool,
}

/// Evaluated half-and-half view.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedHalfAndHalfView {
    pub config_id: String,
    pub config_name: String,
    pub left_topping_ids: Vec<String>,
    pub right_topping_ids: Vec<String>,
    pub surcharge_paise: i64,
    pub left_half_base_paise: i64,
    pub right_half_base_paise: i64,
    pub average_paise: i64,
    pub is_available: bool,
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
    // Phase 8 modifiers
    pub cheese: Option<EvaluatedCheeseView>,
    pub sauce: Option<EvaluatedSauceView>,
    pub standard_toppings: Vec<EvaluatedStandardToppingView>,
    pub half_and_half: Option<EvaluatedHalfAndHalfView>,
    pub quantity: i32,
    pub unit_price_paise: i64,
    pub line_total_paise: i64,
    pub is_available: bool,
    pub issues: Vec<CartValidationIssue>,
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

            let mut sorted_toppings_set = BTreeSet::new();
            for tid_raw in &raw.topping_ids {
                sorted_toppings_set.insert(parse_uuid(tid_raw, "topping_id")?.to_string());
            }
            let topping_ids = sorted_toppings_set
                .into_iter()
                .map(|s| Uuid::parse_str(&s))
                .collect::<Result<Vec<_>, _>>()
                .map_err(|_| DomainError::Validation {
                    code: "INVALID_UUID",
                    message: "Invalid topping_id UUID".to_owned(),
                })?;

            // Parse Phase 8 modifier IDs
            let cheese_id = raw
                .cheese_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| parse_uuid(s, "cheese_id"))
                .transpose()?;
            let sauce_id = raw
                .sauce_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| parse_uuid(s, "sauce_id"))
                .transpose()?;

            let mut standard_topping_ids = Vec::new();
            for tid_raw in &raw.standard_topping_ids {
                standard_topping_ids.push(parse_uuid(tid_raw, "standard_topping_id")?);
            }

            let half_and_half_config_id = raw
                .half_and_half_config_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| parse_uuid(s, "half_and_half_config_id"))
                .transpose()?;

            let mut half_and_half_left_topping_ids = Vec::new();
            for tid_raw in &raw.half_and_half_left_topping_ids {
                half_and_half_left_topping_ids
                    .push(parse_uuid(tid_raw, "half_and_half_left_topping_id")?);
            }

            let mut half_and_half_right_topping_ids = Vec::new();
            for tid_raw in &raw.half_and_half_right_topping_ids {
                half_and_half_right_topping_ids
                    .push(parse_uuid(tid_raw, "half_and_half_right_topping_id")?);
            }

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
                sauce_id,
                standard_topping_ids,
                half_and_half_config_id,
                half_and_half_left_topping_ids,
                half_and_half_right_topping_ids,
            });
        }

        Ok(parsed)
    }

    pub fn merge_duplicate_configurations(items: Vec<ParsedCartItem>) -> Vec<ParsedCartItem> {
        let mut index_by_key: HashMap<ConfigKey, usize> = HashMap::new();
        let mut merged: Vec<ParsedCartItem> = Vec::with_capacity(items.len());

        for item in items {
            let key = ConfigKey {
                product_id: item.product_id,
                variant_id: item.variant_id,
                crust_id: item.crust_id,
                topping_ids: item.topping_ids.clone(),
                cheese_id: item.cheese_id,
                sauce_id: item.sauce_id,
                standard_topping_ids: item.standard_topping_ids.clone(),
                half_and_half_config_id: item.half_and_half_config_id,
                half_and_half_left_topping_ids: item.half_and_half_left_topping_ids.clone(),
                half_and_half_right_topping_ids: item.half_and_half_right_topping_ids.clone(),
            };
            if let Some(&existing_idx) = index_by_key.get(&key) {
                let existing = &mut merged[existing_idx];
                existing.quantity = existing
                    .quantity
                    .saturating_add(item.quantity)
                    .min(MAX_ITEM_QUANTITY);
            } else {
                index_by_key.insert(key, merged.len());
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
            for tid in &item.topping_ids {
                if !lookup.toppings.contains_key(tid) {
                    return Err(DomainError::Validation {
                        code: "INVALID_TOPPING_ID",
                        message: format!("Topping {tid} does not exist."),
                    });
                }
            }

            // Phase 8 modifier validations
            // Cheese validation
            if let Some(cheese_id) = item.cheese_id {
                let cheese = lookup.cheeses.get(&cheese_id);
                if cheese.is_none() || cheese.is_some_and(|c| c.product_id != item.product_id) {
                    return Err(DomainError::Validation {
                        code: "CHEESE_NOT_IN_PRODUCT",
                        message: format!(
                            "Cheese {cheese_id} does not belong to product {}.",
                            item.product_id
                        ),
                    });
                }
                if cheese.is_some_and(|c| !c.is_active) {
                    return Err(DomainError::CheeseUnavailable {
                        code: "CHEESE_UNAVAILABLE",
                        message: format!(
                            "Cheese {} is currently unavailable.",
                            cheese.unwrap().name
                        ),
                    });
                }
            }

            // Sauce validation
            if let Some(sauce_id) = item.sauce_id {
                let sauce = lookup.sauces.get(&sauce_id);
                if sauce.is_none() || sauce.is_some_and(|s| s.product_id != item.product_id) {
                    return Err(DomainError::Validation {
                        code: "SAUCE_NOT_IN_PRODUCT",
                        message: format!(
                            "Sauce {sauce_id} does not belong to product {}.",
                            item.product_id
                        ),
                    });
                }
                if sauce.is_some_and(|s| !s.is_active) {
                    return Err(DomainError::SauceUnavailable {
                        code: "SAUCE_UNAVAILABLE",
                        message: format!("Sauce {} is currently unavailable.", sauce.unwrap().name),
                    });
                }
            }

            // Standard topping validation
            for std_id in &item.standard_topping_ids {
                let std_topping = lookup.standard_toppings.get(std_id);
                if std_topping.is_none()
                    || std_topping.is_some_and(|s| s.product_id != item.product_id)
                {
                    return Err(DomainError::Validation {
                        code: "STANDARD_TOPPING_NOT_IN_PRODUCT",
                        message: format!(
                            "Standard topping {std_id} does not belong to product {}.",
                            item.product_id
                        ),
                    });
                }
                if std_topping.is_some_and(|s| !s.is_active) {
                    return Err(DomainError::StandardToppingUnavailable {
                        code: "STANDARD_TOPPING_UNAVAILABLE",
                        message: format!(
                            "Standard topping {} is currently unavailable.",
                            std_topping.unwrap().name
                        ),
                    });
                }
            }

            // Half-and-half config validation
            if let Some(hah_config_id) = item.half_and_half_config_id {
                let hah_config = lookup.half_and_half_configs.get(&hah_config_id);
                if hah_config.is_none()
                    || hah_config.is_some_and(|c| c.product_id != item.product_id)
                {
                    return Err(DomainError::Validation {
                        code: "HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT",
                        message: format!(
                            "Half-and-half config {hah_config_id} does not belong to product {}.",
                            item.product_id
                        ),
                    });
                }
                if hah_config.is_some_and(|c| !c.is_active) {
                    return Err(DomainError::HalfAndHalfUnavailable {
                        code: "HALF_AND_HALF_UNAVAILABLE",
                        message: format!(
                            "Half-and-half config {} is currently unavailable.",
                            hah_config.unwrap().name
                        ),
                    });
                }
            }

            // Half-and-half topping validation: all topping IDs must be in product_toppings for the product
            let product_toppings = lookup.product_toppings.get(&item.product_id);
            let available_topping_ids: std::collections::HashSet<Uuid> = product_toppings
                .map(|v| v.iter().map(|pt| pt.topping_id).collect())
                .unwrap_or_default();

            for tid in &item.half_and_half_left_topping_ids {
                if !available_topping_ids.contains(tid) {
                    return Err(DomainError::Validation {
                        code: "STANDARD_TOPPING_NOT_IN_PRODUCT",
                        message: format!(
                            "Half-and-half left topping {tid} is not available for product {}.",
                            item.product_id
                        ),
                    });
                }
            }
            for tid in &item.half_and_half_right_topping_ids {
                if !available_topping_ids.contains(tid) {
                    return Err(DomainError::Validation {
                        code: "STANDARD_TOPPING_NOT_IN_PRODUCT",
                        message: format!(
                            "Half-and-half right topping {tid} is not available for product {}.",
                            item.product_id
                        ),
                    });
                }
            }

            // Half-and-half limits validation. Enforce whenever half-and-half topping
            // arrays are present, independent of whether a config ID was supplied —
            // otherwise a payload could bypass limits/overlap by omitting the config ID.
            let has_half_and_half_toppings = !item.half_and_half_left_topping_ids.is_empty()
                || !item.half_and_half_right_topping_ids.is_empty();
            if has_half_and_half_toppings {
                if item.half_and_half_left_topping_ids.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF {
                    return Err(DomainError::HalfAndHalfToppingLimitExceeded {
                        code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
                        message: format!(
                            "Half-and-half left side cannot have more than {} toppings.",
                            MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
                        ),
                    });
                }
                if item.half_and_half_right_topping_ids.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
                {
                    return Err(DomainError::HalfAndHalfToppingLimitExceeded {
                        code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
                        message: format!(
                            "Half-and-half right side cannot have more than {} toppings.",
                            MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
                        ),
                    });
                }

                // Check distinct total
                let mut distinct: std::collections::HashSet<&Uuid> =
                    std::collections::HashSet::new();
                distinct.extend(&item.half_and_half_left_topping_ids);
                distinct.extend(&item.half_and_half_right_topping_ids);
                if distinct.len() > MAX_HALF_AND_HALF_DISTINCT_TOPPINGS {
                    return Err(DomainError::HalfAndHalfToppingLimitExceeded {
                        code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
                        message: format!(
                            "Half-and-half cannot have more than {} distinct toppings total.",
                            MAX_HALF_AND_HALF_DISTINCT_TOPPINGS
                        ),
                    });
                }

                // Check left/right overlap
                let left_set: std::collections::HashSet<_> =
                    item.half_and_half_left_topping_ids.iter().collect();
                let right_set: std::collections::HashSet<_> =
                    item.half_and_half_right_topping_ids.iter().collect();
                if !left_set.is_disjoint(&right_set) {
                    return Err(DomainError::HalfAndHalfLeftRightOverlap {
                        code: "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
                        message: "Half-and-half left and right sides cannot share toppings."
                            .to_owned(),
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

            if !crust.is_some_and(|c| c.is_active) {
                let crust_name = crust.map_or("Selected crust", |c| c.name.as_str());
                issues.push(CartValidationIssue {
                    code: CartValidationIssueCode::CrustUnavailable,
                    message: format!("{crust_name} is currently unavailable."),
                });
            }

            // Extra toppings (legacy)
            let mut topping_views = Vec::with_capacity(input.topping_ids.len());
            let mut extra_topping_extras: Vec<i64> = Vec::with_capacity(input.topping_ids.len());
            for tid in &input.topping_ids {
                let row = lookup.toppings.get(tid);
                let available = row.is_some_and(|t| t.is_active);
                if !available {
                    let t_name = row.map_or("Selected topping", |t| t.name.as_str());
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::ToppingUnavailable,
                        message: format!("{t_name} is no longer available."),
                    });
                }
                let extra = row.map_or(0, |t| t.extra_price_paise);
                extra_topping_extras.push(extra);
                topping_views.push(EvaluatedToppingView {
                    topping_id: tid.to_string(),
                    name: row.map_or_else(|| "Unavailable Topping".to_owned(), |t| t.name.clone()),
                    is_veg: row.is_none_or(|t| t.is_veg),
                    extra_price_paise: extra,
                    is_available: available,
                });
            }

            // Cheese
            let mut cheese_view = None;
            let mut cheese_extra_paise = 0i64;
            if let Some(cheese_id) = input.cheese_id {
                let cheese = lookup.cheeses.get(&cheese_id);
                let available = cheese.is_some_and(|c| c.is_active);
                if !available {
                    let c_name = cheese.map_or("Selected cheese", |c| c.name.as_str());
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::CheeseUnavailable,
                        message: format!("{c_name} is currently unavailable."),
                    });
                }
                let extra = cheese.map_or(0, |c| c.extra_price_paise);
                cheese_extra_paise = extra;
                cheese_view = Some(EvaluatedCheeseView {
                    cheese_id: cheese_id.to_string(),
                    name: cheese
                        .map_or_else(|| "Unavailable Cheese".to_owned(), |c| c.name.clone()),
                    extra_price_paise: extra,
                    is_available: available,
                });
            }

            // Sauce
            let mut sauce_view = None;
            let mut sauce_extra_paise = 0i64;
            if let Some(sauce_id) = input.sauce_id {
                let sauce = lookup.sauces.get(&sauce_id);
                let available = sauce.is_some_and(|s| s.is_active);
                if !available {
                    let s_name = sauce.map_or("Selected sauce", |s| s.name.as_str());
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::SauceUnavailable,
                        message: format!("{s_name} is currently unavailable."),
                    });
                }
                let extra = sauce.map_or(0, |s| s.extra_price_paise);
                sauce_extra_paise = extra;
                sauce_view = Some(EvaluatedSauceView {
                    sauce_id: sauce_id.to_string(),
                    name: sauce.map_or_else(|| "Unavailable Sauce".to_owned(), |s| s.name.clone()),
                    extra_price_paise: extra,
                    is_available: available,
                });
            }

            // Standard toppings
            let mut standard_topping_views = Vec::with_capacity(input.standard_topping_ids.len());
            let mut standard_toppings_selections: Vec<crate::pricing::StandardToppingSelection> =
                Vec::with_capacity(input.standard_topping_ids.len());
            for std_id in &input.standard_topping_ids {
                let std_topping = lookup.standard_toppings.get(std_id);
                let available = std_topping.is_some_and(|s| s.is_active);
                if !available {
                    let s_name =
                        std_topping.map_or("Selected standard topping", |s| s.name.as_str());
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::StandardToppingUnavailable,
                        message: format!("{s_name} is currently unavailable."),
                    });
                }
                let is_default = std_topping.map_or(false, |s| s.is_default);
                let extra = std_topping.map_or(0, |s| s.extra_price_paise);
                let is_removed = false; // In cart input, removed defaults are not sent; they're just absent
                standard_topping_views.push(EvaluatedStandardToppingView {
                    standard_topping_id: std_id.to_string(),
                    name: std_topping.map_or_else(
                        || "Unavailable Standard Topping".to_owned(),
                        |s| s.name.clone(),
                    ),
                    is_veg: std_topping.is_none_or(|s| s.is_veg),
                    extra_price_paise: extra,
                    is_default,
                    is_removed,
                    is_available: available,
                });
                standard_toppings_selections.push(crate::pricing::StandardToppingSelection {
                    id: *std_id,
                    extra_price_paise: extra,
                    is_default,
                    is_removed,
                });
            }

            // Half-and-half
            let mut half_and_half_view = None;
            let mut half_and_half_input = None;
            if let Some(hah_config_id) = input.half_and_half_config_id {
                let hah_config = lookup.half_and_half_configs.get(&hah_config_id);
                let available = hah_config.is_some_and(|c| c.is_active);
                if !available {
                    let c_name = hah_config.map_or("Selected half-and-half", |c| c.name.as_str());
                    issues.push(CartValidationIssue {
                        code: CartValidationIssueCode::HalfAndHalfUnavailable,
                        message: format!("{c_name} is currently unavailable."),
                    });
                }

                // Get topping extras for left/right
                let mut left_extras: Vec<i64> = Vec::new();
                for tid in &input.half_and_half_left_topping_ids {
                    let topping = lookup.toppings.get(tid);
                    let extra = topping.map_or(0, |t| t.extra_price_paise);
                    left_extras.push(extra);
                }
                let mut right_extras: Vec<i64> = Vec::new();
                for tid in &input.half_and_half_right_topping_ids {
                    let topping = lookup.toppings.get(tid);
                    let extra = topping.map_or(0, |t| t.extra_price_paise);
                    right_extras.push(extra);
                }

                let surcharge = hah_config.map_or(0, |c| c.half_and_half_surcharge_paise);
                half_and_half_input = Some(crate::pricing::HalfAndHalfInput {
                    left_topping_extras: left_extras.clone(),
                    right_topping_extras: right_extras.clone(),
                    surcharge_paise: surcharge,
                });

                // Compute breakdown for view
                let base_price = variant.map_or(0, |v| v.price_paise);
                let crust_extra = crust.map_or(0, |c| c.extra_price_paise);
                let mut left_sum: i64 = 0;
                for extra in &left_extras {
                    left_sum = left_sum
                        .checked_add(*extra)
                        .ok_or(DomainError::ArithmeticOverflow("view_left_sum"))?;
                }
                let mut right_sum: i64 = 0;
                for extra in &right_extras {
                    right_sum = right_sum
                        .checked_add(*extra)
                        .ok_or(DomainError::ArithmeticOverflow("view_right_sum"))?;
                }
                let left_half_base = base_price
                    .checked_add(crust_extra)
                    .and_then(|v| v.checked_add(left_sum))
                    .ok_or(DomainError::ArithmeticOverflow("view_left_half_base"))?;
                let right_half_base = base_price
                    .checked_add(crust_extra)
                    .and_then(|v| v.checked_add(right_sum))
                    .ok_or(DomainError::ArithmeticOverflow("view_right_half_base"))?;
                let average = i128::from(left_half_base)
                    .checked_add(i128::from(right_half_base))
                    .and_then(|v| v.checked_add(1))
                    .and_then(|v| v.checked_div(2))
                    .ok_or(DomainError::ArithmeticOverflow("view_half_average"))?;
                let average_paise = i64::try_from(average)
                    .map_err(|_| DomainError::ArithmeticOverflow("view_half_average_i64"))?;

                half_and_half_view = Some(EvaluatedHalfAndHalfView {
                    config_id: hah_config_id.to_string(),
                    config_name: hah_config.map_or_else(
                        || "Unavailable Half-and-Half".to_owned(),
                        |c| c.name.clone(),
                    ),
                    left_topping_ids: input
                        .half_and_half_left_topping_ids
                        .iter()
                        .map(|u| u.to_string())
                        .collect(),
                    right_topping_ids: input
                        .half_and_half_right_topping_ids
                        .iter()
                        .map(|u| u.to_string())
                        .collect(),
                    surcharge_paise: surcharge,
                    left_half_base_paise: left_half_base,
                    right_half_base_paise: right_half_base,
                    average_paise,
                    is_available: available,
                });
            }

            // Calculate standard toppings extra
            let standard_toppings_extra_paise =
                PricingEngine::calculate_standard_toppings_extra_paise(
                    &standard_toppings_selections,
                )?;

            // Calculate unit price using new pricing engine
            let base_price = variant.map_or(0, |v| v.price_paise);
            let crust_extra = crust.map_or(0, |c| c.extra_price_paise);

            let unit_price = PricingEngine::calculate_unit_price_paise(
                base_price,
                crust_extra,
                cheese_extra_paise,
                sauce_extra_paise,
                standard_toppings_extra_paise,
                &extra_topping_extras,
                half_and_half_input.as_ref(),
            )?;

            let line_total = PricingEngine::calculate_line_total_paise(unit_price, input.quantity)?;

            let is_available = issues.is_empty();
            if is_available {
                subtotal_paise = subtotal_paise
                    .checked_add(line_total)
                    .ok_or(DomainError::ArithmeticOverflow("cart_subtotal"))?;
            } else {
                unavailable_count += 1;
            }
            total_item_count = total_item_count
                .checked_add(input.quantity)
                .ok_or(DomainError::ArithmeticOverflow("cart_total_item_count"))?;

            let item_id = input
                .item_id
                .clone()
                .unwrap_or_else(|| format!("line-{idx}"));

            evaluated_items.push(EvaluatedCartItem {
                item_id,
                product_id: input.product_id.to_string(),
                product_name: prod
                    .map_or_else(|| "Unavailable Pizza".to_owned(), |p| p.name.clone()),
                is_veg: prod.is_none_or(|p| p.is_veg),
                is_taxable: prod.is_none_or(|p| p.is_taxable),
                image_url: prod.and_then(|p| p.image_url.clone()),
                variant_id: input.variant_id.to_string(),
                size_code: variant.map(|v| v.size_code),
                base_price_paise: base_price,
                crust_id: input.crust_id.to_string(),
                crust_name: crust
                    .map_or_else(|| "Unavailable Crust".to_owned(), |c| c.name.clone()),
                crust_extra_price_paise: crust_extra,
                toppings: topping_views,
                cheese: cheese_view,
                sauce: sauce_view,
                standard_toppings: standard_topping_views,
                half_and_half: half_and_half_view,
                quantity: input.quantity,
                unit_price_paise: unit_price,
                line_total_paise: line_total,
                is_available,
                issues,
            });
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

fn parse_uuid(raw: &str, field: &'static str) -> Result<Uuid, DomainError> {
    Uuid::parse_str(raw).map_err(|_| DomainError::Validation {
        code: "INVALID_UUID",
        message: format!("Invalid {field} UUID: {raw}"),
    })
}

const fn size_code_str(size: SizeCode) -> &'static str {
    match size {
        SizeCode::Regular => "REGULAR",
        SizeCode::Medium => "MEDIUM",
        SizeCode::Large => "LARGE",
    }
}
