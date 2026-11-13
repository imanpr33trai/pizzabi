package com.example.checkout

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse

interface PricingRepository {
  suspend fun fetchAvailableCoupons(subtotalPaise: Long?): Result<AvailableCouponsResponse>
  suspend fun fetchPricingQuote(request: PricingQuoteRequest): Result<PricingQuoteResponse>
}

class NetworkPricingRepository(
  private val apiClient: PizzoraApiClient,
) : PricingRepository {
  override suspend fun fetchAvailableCoupons(subtotalPaise: Long?): Result<AvailableCouponsResponse> =
    runCatching { apiClient.getAvailableCoupons(subtotalPaise) }

  override suspend fun fetchPricingQuote(request: PricingQuoteRequest): Result<PricingQuoteResponse> =
    runCatching { apiClient.quotePricing(request) }
}
