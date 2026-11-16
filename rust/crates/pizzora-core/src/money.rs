use crate::error::DomainError;

pub struct Money;

impl Money {
    const BPS_DENOMINATOR: i128 = 10_000;
    const HALF_BPS: i128 = 5_000;

    /// Computes `amount_paise * bps / 10_000` using integer HALF_UP rounding.
    /// Rejects negative inputs and reports arithmetic overflow beyond `i64`.
    pub fn bps_of(amount_paise: i64, bps: i32) -> Result<i64, DomainError> {
        if amount_paise < 0 {
            return Err(DomainError::NegativeAmount("amount_paise"));
        }
        if bps < 0 {
            return Err(DomainError::NegativeAmount("bps"));
        }
        if amount_paise == 0 || bps == 0 {
            return Ok(0);
        }

        let numerator = i128::from(amount_paise)
            .checked_mul(i128::from(bps))
            .and_then(|v| v.checked_add(Self::HALF_BPS))
            .ok_or(DomainError::ArithmeticOverflow("Money::bps_of"))?;
        let rounded = numerator / Self::BPS_DENOMINATOR;

        i64::try_from(rounded).map_err(|_| DomainError::ArithmeticOverflow("Money::bps_of"))
    }

    /// Formats integer paise into deterministic INR currency text (e.g., `₹299.00`, `-₹12.50`).
    pub fn format_inr(paise: i64) -> String {
        let sign = if paise < 0 { "-" } else { "" };
        let abs_paise = paise.unsigned_abs();
        let rupees = abs_paise / 100;
        let rem_paise = abs_paise % 100;
        format!("{sign}₹{rupees}.{rem_paise:02}")
    }
}
