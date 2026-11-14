package com.example.checkout

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse

interface PricingRepository {
  suspend fun fetchAvailableCoupons(subtotalPaise: Long?): Result<AvailableCouponsResponse>
  suspend fun fetchPricingQuote(request: PricingQuoteRequest): Result<PricingQuoteResponse>
  suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse>
}

class NetworkPricingRepository(
  private val apiClient: PizzoraApiClient,
) : PricingRepository {
  override suspend fun fetchAvailableCoupons(subtotalPaise: Long?): Result<AvailableCouponsResponse> =
    runCatching { apiClient.getAvailableCoupons(subtotalPaise) }

  override suspend fun fetchPricingQuote(request: PricingQuoteRequest): Result<PricingQuoteResponse> =
    runCatching { apiClient.quotePricing(request) }

  override suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse> =
    runCatching { apiClient.checkDeliveryZone(pincode) }
}
