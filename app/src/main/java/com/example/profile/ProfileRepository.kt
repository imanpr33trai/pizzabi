package com.example.profile

import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import kotlin.coroutines.cancellation.CancellationException

interface ProfileRepository {
  suspend fun getProfile(): Result<UserProfileDto>
  suspend fun updateProfile(request: UpdateProfileRequest): Result<UserProfileDto>
  suspend fun getAddresses(): Result<List<AddressDto>>
  suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto>
  suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): Result<AddressDto>
  suspend fun deleteAddress(addressId: String): Result<Unit>
  suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse>
}

class NetworkProfileRepository(
  private val apiClient: PizzoraApiClient,
) : ProfileRepository {
  override suspend fun getProfile(): Result<UserProfileDto> = runSafe {
    apiClient.getMe()
  }

  override suspend fun updateProfile(request: UpdateProfileRequest): Result<UserProfileDto> = runSafe {
    apiClient.updateMe(request)
  }

  override suspend fun getAddresses(): Result<List<AddressDto>> = runSafe {
    apiClient.getAddresses()
  }

  override suspend fun createAddress(request: UpsertAddressRequest): Result<AddressDto> = runSafe {
    apiClient.createAddress(request)
  }

  override suspend fun updateAddress(addressId: String, request: UpsertAddressRequest): Result<AddressDto> = runSafe {
    apiClient.updateAddress(addressId, request)
  }

  override suspend fun deleteAddress(addressId: String): Result<Unit> = runSafe {
    apiClient.deleteAddress(addressId)
  }

  override suspend fun checkDeliveryZone(pincode: String): Result<DeliveryZoneCheckResponse> = runSafe {
    apiClient.checkDeliveryZone(pincode)
  }

  private inline fun <T> runSafe(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }
}
