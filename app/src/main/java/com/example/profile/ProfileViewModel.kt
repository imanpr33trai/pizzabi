package com.example.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.UpdateProfileRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserProfileDto
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
  val profile: UserProfileDto? = null,
  val addresses: List<AddressDto> = emptyList(),
  val isLoading: Boolean = false,
  val isSavingProfile: Boolean = false,
  val isEditProfileDialogOpen: Boolean = false,
  val isAddressDialogOpen: Boolean = false,
  val editingAddress: AddressDto? = null,
  val isSavingAddress: Boolean = false,
  val deletingAddressId: String? = null,
  val pincodeCheckInput: String = "",
  val isCheckingPincode: Boolean = false,
  val pincodeCheckResult: DeliveryZoneCheckResponse? = null,
  val pincodeCheckError: String? = null,
  val errorMessage: String? = null,
  val dialogError: String? = null,
)

class ProfileViewModel(
  private val profileRepository: ProfileRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ProfileUiState())
  val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val events: SharedFlow<String> = _events.asSharedFlow()

  fun loadProfileAndAddresses(fallbackUser: UserProfileDto? = null) {
    viewModelScope.launch {
      _uiState.update { state ->
        state.copy(
          profile = state.profile ?: fallbackUser,
          isLoading = true,
          errorMessage = null,
        )
      }
      val profileDef = async { profileRepository.getProfile() }
      val addressesDef = async { profileRepository.getAddresses() }
      val profileRes = profileDef.await()
      val addressesRes = addressesDef.await()

      _uiState.update { state ->
        state.copy(
          isLoading = false,
          profile = profileRes.getOrNull() ?: state.profile ?: fallbackUser,
          addresses = addressesRes.getOrNull() ?: state.addresses,
          errorMessage = addressesRes.exceptionOrNull()?.message
            ?: profileRes.exceptionOrNull()?.message,
        )
      }
    }
  }

  fun openEditProfileDialog() {
    _uiState.update { it.copy(isEditProfileDialogOpen = true, dialogError = null) }
  }

  fun dismissEditProfileDialog() {
    _uiState.update { it.copy(isEditProfileDialogOpen = false, dialogError = null) }
  }

  fun saveProfile(fullName: String, phone: String) {
    val cleanName = fullName.trim()
    val cleanPhone = phone.trim()
    if (cleanName.length < 2) {
      _uiState.update { it.copy(dialogError = "Full name must be at least 2 characters.") }
      return
    }
    if (cleanPhone.length < 8) {
      _uiState.update { it.copy(dialogError = "Enter a valid phone number.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSavingProfile = true, dialogError = null) }
      profileRepository.updateProfile(UpdateProfileRequest(fullName = cleanName, phone = cleanPhone))
        .onSuccess { updated ->
          _uiState.update {
            it.copy(
              profile = updated,
              isSavingProfile = false,
              isEditProfileDialogOpen = false,
              dialogError = null,
            )
          }
          _events.tryEmit("Profile updated")
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isSavingProfile = false,
              dialogError = err.message ?: "Unable to update profile.",
            )
          }
        }
    }
  }

  fun openAddAddressDialog() {
    _uiState.update {
      it.copy(
        isAddressDialogOpen = true,
        editingAddress = null,
        dialogError = null,
      )
    }
  }

  fun openEditAddressDialog(address: AddressDto) {
    _uiState.update {
      it.copy(
        isAddressDialogOpen = true,
        editingAddress = address,
        dialogError = null,
      )
    }
  }

  fun dismissAddressDialog() {
    _uiState.update {
      it.copy(
        isAddressDialogOpen = false,
        editingAddress = null,
        dialogError = null,
      )
    }
  }

  fun saveAddress(
    label: String,
    line1: String,
    line2: String?,
    landmark: String?,
    pincode: String,
    city: String,
    isDefault: Boolean,
  ) {
    val cleanLabel = label.trim()
    val cleanLine1 = line1.trim()
    val cleanLine2 = line2?.trim()?.takeIf { it.isNotEmpty() }
    val cleanLandmark = landmark?.trim()?.takeIf { it.isNotEmpty() }
    val cleanPin = pincode.trim()
    val cleanCity = city.trim()

    if (cleanLabel.isEmpty() || cleanLine1.isEmpty() || cleanCity.isEmpty()) {
      _uiState.update { it.copy(dialogError = "Label, street address, and city are required.") }
      return
    }
    if (!cleanPin.matches(Regex("^[1-9][0-9]{5}$"))) {
      _uiState.update { it.copy(dialogError = "Enter a valid 6-digit Indian pincode.") }
      return
    }

    val editing = _uiState.value.editingAddress
    val request = UpsertAddressRequest(
      label = cleanLabel,
      line1 = cleanLine1,
      line2 = cleanLine2,
      landmark = cleanLandmark,
      pincode = cleanPin,
      city = cleanCity,
      isDefault = isDefault || (_uiState.value.addresses.isEmpty() && editing == null),
    )

    viewModelScope.launch {
      _uiState.update { it.copy(isSavingAddress = true, dialogError = null) }
      val result = if (editing == null) {
        profileRepository.createAddress(request)
      } else {
        profileRepository.updateAddress(editing.id, request)
      }

      result
        .onSuccess { saved ->
          val refreshed = profileRepository.getAddresses().getOrNull()
          _uiState.update { state ->
            val updatedList = refreshed ?: run {
              val base = if (editing == null) {
                listOf(saved) + state.addresses
              } else {
                state.addresses.map { if (it.id == saved.id) saved else it }
              }
              if (saved.isDefault) {
                base.map { if (it.id == saved.id) saved else it.copy(isDefault = false) }
              } else {
                base
              }
            }
            state.copy(
              addresses = updatedList,
              isSavingAddress = false,
              isAddressDialogOpen = false,
              editingAddress = null,
              dialogError = null,
            )
          }
          _events.tryEmit(if (editing == null) "Address saved" else "Address updated")
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isSavingAddress = false,
              dialogError = err.message ?: "Unable to save address.",
            )
          }
        }
    }
  }

  fun setDefaultAddress(address: AddressDto) {
    if (address.isDefault) return
    viewModelScope.launch {
      _uiState.update { it.copy(isSavingAddress = true, errorMessage = null) }
      profileRepository.updateAddress(
        address.id,
        UpsertAddressRequest(
          label = address.label,
          line1 = address.line1,
          line2 = address.line2,
          landmark = address.landmark,
          pincode = address.pincode,
          city = address.city,
          isDefault = true,
        ),
      )
        .onSuccess { updated ->
          val refreshed = profileRepository.getAddresses().getOrNull()
          _uiState.update { state ->
            state.copy(
              isSavingAddress = false,
              addresses = refreshed ?: state.addresses.map {
                if (it.id == updated.id) updated else it.copy(isDefault = false)
              },
            )
          }
          _events.tryEmit("${address.label} set as default address")
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isSavingAddress = false,
              errorMessage = err.message ?: "Could not set default address.",
            )
          }
        }
    }
  }

  fun deleteAddress(addressId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(deletingAddressId = addressId, errorMessage = null) }
      profileRepository.deleteAddress(addressId)
        .onSuccess {
          val refreshed = profileRepository.getAddresses().getOrNull()
          _uiState.update { state ->
            state.copy(
              deletingAddressId = null,
              addresses = refreshed ?: state.addresses.filterNot { it.id == addressId },
            )
          }
          _events.tryEmit("Address removed")
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              deletingAddressId = null,
              errorMessage = err.message ?: "Unable to delete address.",
            )
          }
        }
    }
  }

  fun onPincodeCheckInputChange(value: String) {
    val clean = value.filter { it.isDigit() }.take(6)
    _uiState.update {
      it.copy(
        pincodeCheckInput = clean,
        pincodeCheckError = null,
      )
    }
  }

  fun checkPincodeServiceability(pincodeOverride: String? = null) {
    val cleanPin = (pincodeOverride ?: _uiState.value.pincodeCheckInput).trim()
    if (!cleanPin.matches(Regex("^[1-9][0-9]{5}$"))) {
      _uiState.update {
        it.copy(
          pincodeCheckResult = null,
          pincodeCheckError = "Enter a valid 6-digit Indian pincode.",
        )
      }
      return
    }
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          pincodeCheckInput = cleanPin,
          isCheckingPincode = true,
          pincodeCheckError = null,
        )
      }
      profileRepository.checkDeliveryZone(cleanPin)
        .onSuccess { response ->
          _uiState.update {
            it.copy(
              isCheckingPincode = false,
              pincodeCheckResult = response,
              pincodeCheckError = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isCheckingPincode = false,
              pincodeCheckError = err.message ?: "Unable to verify pincode right now.",
            )
          }
        }
    }
  }
}
