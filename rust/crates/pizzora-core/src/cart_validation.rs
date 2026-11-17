use std::collections::{BTreeSet, HashMap};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DomainError;
use crate::pricing::PricingEngine;

pub const MAX_CART_LINE_ITEMS: usize = 50;
pub const MAX_ITEM_QUANTITY: i32 = 25;

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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartValidationIssue {
    pub code: CartValidationIssueCode,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartItemInput {
    pub id: Option<String>,
    pub product_id: String,
    pub variant_id: String,
    pub crust_id: String,
    pub topping_ids: Vec<String>,
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
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct ConfigKey {
    product_id: Uuid,
    variant_id: Uuid,
    crust_id: Uuid,
    topping_ids: Vec<Uuid>,
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

#[derive(Debug, Clone, Default)]
pub struct CatalogRefLookup {
    pub products: HashMap<Uuid, CatalogProductRef>,
    pub variants: HashMap<Uuid, CatalogVariantRef>,
    pub crusts: HashMap<Uuid, CatalogCrustRef>,
    pub toppings: HashMap<Uuid, CatalogToppingRef>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvaluatedToppingView {
    pub topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
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

            let mut topping_views = Vec::with_capacity(input.topping_ids.len());
            let mut toppings_price: i64 = 0;
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
                toppings_price = toppings_price
                    .checked_add(extra)
                    .ok_or(DomainError::ArithmeticOverflow("toppings_price"))?;
                topping_views.push(EvaluatedToppingView {
                    topping_id: tid.to_string(),
                    name: row.map_or_else(|| "Unavailable Topping".to_owned(), |t| t.name.clone()),
                    is_veg: row.is_none_or(|t| t.is_veg),
                    extra_price_paise: extra,
                    is_available: available,
                });
            }

            let base_price = variant.map_or(0, |v| v.price_paise);
            let crust_price = crust.map_or(0, |c| c.extra_price_paise);
            let unit_price = base_price
                .checked_add(crust_price)
                .and_then(|v| v.checked_add(toppings_price))
                .ok_or(DomainError::ArithmeticOverflow("cart_unit_price"))?;
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
                crust_extra_price_paise: crust_price,
                toppings: topping_views,
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
