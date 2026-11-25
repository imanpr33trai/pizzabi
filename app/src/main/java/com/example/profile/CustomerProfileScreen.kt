package com.example.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.components.PizzoraConfirmationDialog
import com.example.ui.theme.PizzoraMonoBody
import com.example.update.AppUpdatesSettingsCard
import com.example.update.UpdateUiState
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.UserProfileDto
import kotlinx.coroutines.delay

@Composable
fun CustomerProfileScreen(
  uiState: ProfileUiState,
  onRefresh: () -> Unit,
  onOpenEditProfile: () -> Unit,
  onDismissEditProfile: () -> Unit,
  onSaveProfile: (fullName: String, phone: String) -> Unit,
  onOpenAddAddress: () -> Unit,
  onOpenEditAddress: (AddressDto) -> Unit,
  onDismissAddressDialog: () -> Unit,
  onSaveAddress: (
    label: String,
    line1: String,
    line2: String?,
    landmark: String?,
    pincode: String,
    city: String,
    isDefault: Boolean,
  ) -> Unit,
  onSetDefaultAddress: (AddressDto) -> Unit,
  onDeleteAddress: (String) -> Unit,
  onPincodeInputChange: (String) -> Unit,
  onCheckPincode: () -> Unit,
  onCheckDeliveryPincode: (String) -> Unit,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  var showSignOutConfirm by rememberSaveable { mutableStateOf(false) }
  var pendingDeleteAddressId by rememberSaveable { mutableStateOf<String?>(null) }
  val editProfileReturnFocus = remember { FocusRequester() }
  val addressReturnFocus = remember { FocusRequester() }
  var editDialogWasOpen by remember { mutableStateOf(false) }
  var addressDialogWasOpen by remember { mutableStateOf(false) }
  var addressDialogReturnTargetId by rememberSaveable { mutableStateOf<String?>(null) }

  LaunchedEffect(uiState.isEditProfileDialogOpen) {
    if (editDialogWasOpen && !uiState.isEditProfileDialogOpen) {
      delay(0)
      editProfileReturnFocus.requestFocus()
    }
    editDialogWasOpen = uiState.isEditProfileDialogOpen
  }

  LaunchedEffect(uiState.isAddressDialogOpen, uiState.editingAddress?.id) {
    if (uiState.isAddressDialogOpen) {
      addressDialogReturnTargetId = uiState.editingAddress?.id
    }
    if (addressDialogWasOpen && !uiState.isAddressDialogOpen) {
      delay(0)
      addressReturnFocus.requestFocus()
    }
    addressDialogWasOpen = uiState.isAddressDialogOpen
  }

  Box(modifier = modifier.fillMaxSize()) {
  Column(
    modifier = Modifier.fillMaxSize().testTag("customer_profile_screen"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (uiState.isLoading || uiState.isSavingAddress || uiState.isSavingProfile) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .testTag("profile_lazy_column"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!uiState.errorMessage.isNullOrBlank()) {
        item(key = "profile_error_banner") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("profile_error_banner"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = uiState.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              TextButton(
                onClick = onRefresh,
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Text(stringResource(R.string.action_retry))
              }
            }
          }
        }
      }

      item(key = "profile_account_card") {
        AccountProfileCard(
          profile = uiState.profile,
          onEdit = onOpenEditProfile,
          returnFocusRequester = editProfileReturnFocus,
        )
      }

      item(key = "profile_pincode_checker_card") {
        PincodeServiceabilityCard(
          uiState = uiState,
          onPincodeInputChange = onPincodeInputChange,
          onCheckPincode = onCheckPincode,
        )
      }

      item(key = "profile_addresses_header") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = stringResource(R.string.profile_saved_addresses_title, uiState.addresses.size),
              style = MaterialTheme.typography.titleMedium,
            )
          }
          Button(
            onClick = {
              if (addressDialogReturnTargetId == null) addressReturnFocus.requestFocus()
              onOpenAddAddress()
            },
            modifier = Modifier
              .then(if (addressDialogReturnTargetId == null) Modifier.focusRequester(addressReturnFocus) else Modifier)
              .minimumInteractiveComponentSize()
              .testTag("profile_add_address_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(4.dp))
            Text(stringResource(R.string.action_add_address))
          }
        }
      }

      if (uiState.addresses.isEmpty()) {
        item(key = "profile_addresses_empty") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag("profile_addresses_empty"),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(stringResource(R.string.profile_no_addresses_title), style = MaterialTheme.typography.titleSmall)
              Text(
                text = stringResource(R.string.profile_no_addresses_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(uiState.addresses, key = { it.id }) { address ->
          SavedAddressCard(
            address = address,
            isDeleting = uiState.deletingAddressId == address.id,
            isAddressDialogReturnTarget = addressDialogReturnTargetId == address.id,
            addressReturnFocusRequester = addressReturnFocus,
            onSetDefault = { onSetDefaultAddress(address) },
            onEdit = { onOpenEditAddress(address) },
            onCheckDelivery = { onCheckDeliveryPincode(address.pincode) },
            onDelete = { pendingDeleteAddressId = address.id },
          )
        }
      }

      if (updateUiState != null) {
        item(key = "profile_app_updates_card") {
          AppUpdatesSettingsCard(
            uiState = updateUiState,
            onCheckForUpdates = onCheckForUpdates,
            onOpenUpdateDialog = onOpenUpdateDialog,
            onRetry = onRetryUpdate,
          )
        }
      }

      item(key = "profile_account_actions") {
        AccountActionsSection(onSignOut = { showSignOutConfirm = true })
      }

      item(key = "profile_bottom_spacer") {
        Spacer(modifier = Modifier.height(72.dp).testTag("profile_bottom_spacer"))
      }
    }
  }

  if (uiState.isEditProfileDialogOpen) {
    EditProfileDialog(
      profile = uiState.profile,
      isSaving = uiState.isSavingProfile,
      error = uiState.dialogError,
      onSave = onSaveProfile,
      onDismiss = onDismissEditProfile,
    )
  }

  if (uiState.isAddressDialogOpen) {
    AddressEditorDialog(
      editing = uiState.editingAddress,
      isSaving = uiState.isSavingAddress,
      error = uiState.dialogError,
      onSave = onSaveAddress,
      onDismiss = onDismissAddressDialog,
    )
  }

  val pendingDeleteAddress = pendingDeleteAddressId?.let { addressId ->
    uiState.addresses.firstOrNull { it.id == addressId }
  }
  if (pendingDeleteAddress != null) {
    PizzoraConfirmationDialog(
      title = stringResource(R.string.profile_delete_address_title, pendingDeleteAddress.label),
      body = stringResource(
        if (pendingDeleteAddress.isDefault) {
          R.string.profile_delete_default_address_body
        } else {
          R.string.profile_delete_address_body
        },
      ),
      confirmLabel = stringResource(R.string.action_delete),
      onConfirm = {
        val addressId = pendingDeleteAddress.id
        pendingDeleteAddressId = null
        onDeleteAddress(addressId)
      },
      onDismiss = { pendingDeleteAddressId = null },
      isDestructive = true,
      isSubmitting = uiState.deletingAddressId == pendingDeleteAddress.id,
      confirmTestTag = "confirm_delete_address_button",
      dismissTestTag = "cancel_delete_address_button",
    )
  }

  if (showSignOutConfirm) {
    PizzoraConfirmationDialog(
      title = stringResource(R.string.sign_out_dialog_title),
      body = stringResource(R.string.sign_out_dialog_body),
      confirmLabel = stringResource(R.string.action_sign_out),
      onConfirm = {
        showSignOutConfirm = false
        onSignOut()
      },
      onDismiss = { showSignOutConfirm = false },
      isDestructive = true,
      confirmTestTag = "confirm_sign_out_button",
      dismissTestTag = "cancel_sign_out_button",
    )
  }
  }
}

@Composable
private fun AccountProfileCard(
  profile: UserProfileDto?,
  onEdit: () -> Unit,
  returnFocusRequester: FocusRequester,
) {
  val monogram = profile?.fullName
    ?.trim()
    ?.split(Regex("\\s+"))
    ?.filter { it.isNotBlank() }
    ?.take(2)
    ?.mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }
    ?.joinToString("")
    ?.takeIf { it.isNotBlank() }
    ?: "P"

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    modifier = Modifier.fillMaxWidth().testTag("profile_account_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(48.dp).testTag("profile_monogram"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = monogram,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
          Column {
            Text(
              text = profile?.fullName ?: stringResource(R.string.profile_loading_name),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.testTag("profile_full_name_text"),
            )
            Text(
              text = profile?.email.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        OutlinedButton(
          onClick = {
            returnFocusRequester.requestFocus()
            onEdit()
          },
          modifier = Modifier
            .focusRequester(returnFocusRequester)
            .minimumInteractiveComponentSize()
            .testTag("edit_profile_button"),
        ) {
          Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.size(4.dp))
          Text(stringResource(R.string.action_edit))
        }
      }

      Text(
        text = stringResource(R.string.profile_phone_fmt, profile?.phone.orEmpty()),
        style = PizzoraMonoBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("profile_phone_text"),
      )
    }
  }
}

@Composable
private fun AccountActionsSection(onSignOut: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    Text(
      text = stringResource(R.string.profile_account_actions_title),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 4.dp),
    )
    Surface(
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().testTag("profile_account_actions"),
    ) {
      TextButton(
        onClick = onSignOut,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("profile_sign_out_button"),
      ) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(R.string.action_sign_out))
      }
    }
  }
}

@Composable
private fun PincodeServiceabilityCard(
  uiState: ProfileUiState,
  onPincodeInputChange: (String) -> Unit,
  onCheckPincode: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    modifier = Modifier.fillMaxWidth().testTag("profile_pincode_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(stringResource(R.string.profile_pincode_checker_title), style = MaterialTheme.typography.titleSmall)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = uiState.pincodeCheckInput,
          onValueChange = onPincodeInputChange,
          label = { Text(stringResource(R.string.pincode_label)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
          ),
          modifier = Modifier.weight(1f).testTag("profile_pincode_input"),
        )
        OutlinedButton(
          onClick = onCheckPincode,
          enabled = !uiState.isCheckingPincode,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_check_pincode_button"),
        ) {
          Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.size(4.dp))
          Text(stringResource(R.string.action_check))
        }
      }

      if (!uiState.pincodeCheckError.isNullOrBlank()) {
        Text(
          text = uiState.pincodeCheckError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("profile_pincode_error_text"),
        )
      }

      uiState.pincodeCheckResult?.let { result ->
        Surface(
          color = if (result.isServiceable) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.errorContainer
          },
          shape = MaterialTheme.shapes.small,
          modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("profile_pincode_result_banner"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = if (result.isServiceable) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
              contentDescription = null,
              tint = if (result.isServiceable) {
                MaterialTheme.colorScheme.onSecondaryContainer
              } else {
                MaterialTheme.colorScheme.onErrorContainer
              },
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = result.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.isServiceable) {
                  MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                  MaterialTheme.colorScheme.onErrorContainer
                },
              )
              result.zone?.let { zone ->
                Text(
                  text = "Min ${Money.formatInr(zone.minOrderPaise)} • Fee ${Money.formatInr(zone.deliveryFeePaise)} • ${zone.estimatedMins} mins",
                  style = PizzoraMonoBody,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SavedAddressCard(
  address: AddressDto,
  isDeleting: Boolean,
  isAddressDialogReturnTarget: Boolean,
  addressReturnFocusRequester: FocusRequester,
  onSetDefault: () -> Unit,
  onEdit: () -> Unit,
  onCheckDelivery: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(
      width = 1.dp,
      color = if (address.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    ),
    modifier = Modifier.fillMaxWidth().testTag("profile_address_card_${address.id}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(address.label, style = MaterialTheme.typography.titleSmall)
          if (address.isDefault) {
            Surface(
              color = MaterialTheme.colorScheme.primaryContainer,
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = stringResource(R.string.address_default_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                  .padding(horizontal = 6.dp, vertical = 2.dp)
                  .testTag("profile_default_badge_${address.id}"),
              )
            }
          }
        }

        Row {
          IconButton(
            onClick = {
              if (isAddressDialogReturnTarget) addressReturnFocusRequester.requestFocus()
              onEdit()
            },
            modifier = Modifier
              .then(if (isAddressDialogReturnTarget) Modifier.focusRequester(addressReturnFocusRequester) else Modifier)
              .minimumInteractiveComponentSize()
              .testTag("profile_edit_address_${address.id}"),
          ) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
          }
          IconButton(
            onClick = onDelete,
            enabled = !isDeleting,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_delete_address_${address.id}"),
          ) {
            Icon(
              Icons.Filled.DeleteOutline,
              contentDescription = stringResource(R.string.action_remove),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      val details = buildString {
        append(address.line1)
        if (!address.line2.isNullOrBlank()) append(", ${address.line2}")
        if (!address.landmark.isNullOrBlank()) append(" (${address.landmark})")
        append(", ${address.city} - ${address.pincode}")
      }
      Text(
        text = details,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onCheckDelivery,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_check_delivery_${address.id}"),
        ) {
          Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.size(4.dp))
          Text(stringResource(R.string.profile_check_delivery))
        }
        if (!address.isDefault) {
          OutlinedButton(
            onClick = onSetDefault,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_set_default_${address.id}"),
          ) {
            Text(stringResource(R.string.action_set_default))
          }
        }
      }
    }
  }
}

@Composable
private fun EditProfileDialog(
  profile: UserProfileDto?,
  isSaving: Boolean,
  error: String?,
  onSave: (fullName: String, phone: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var fullName by rememberSaveable(profile?.id) { mutableStateOf(profile?.fullName.orEmpty()) }
  var phone by rememberSaveable(profile?.id) { mutableStateOf(profile?.phone.orEmpty()) }
  var showValidation by rememberSaveable(profile?.id) { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val nameError = stringResource(R.string.profile_error_name)
  val phoneError = stringResource(R.string.profile_error_phone)
  val fullNameInvalid = fullName.trim().length < 2
  val phoneInvalid = phone.trim().length < 8

  fun submit() {
    showValidation = true
    keyboardController?.hide()
    if (!fullNameInvalid && !phoneInvalid) {
      onSave(fullName.trim(), phone.trim())
    }
  }

  ProfileDialogScaffold(
    title = stringResource(R.string.profile_edit_title),
    onDismiss = onDismiss,
    testTag = "profile_edit_dialog",
    confirmButton = {
      Button(
        onClick = ::submit,
        enabled = !isSaving,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_edit_save_button"),
      ) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Text(stringResource(R.string.action_close))
      }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!error.isNullOrBlank()) {
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("profile_edit_dialog_error"),
        )
      }
      OutlinedTextField(
        value = fullName,
        onValueChange = { fullName = it },
        label = { Text(stringResource(R.string.label_full_name)) },
        singleLine = true,
        isError = showValidation && fullNameInvalid,
        supportingText = {
          if (showValidation && fullNameInvalid) {
            Text(nameError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_name_input"),
      )
      OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(stringResource(R.string.label_phone)) },
        singleLine = true,
        isError = showValidation && phoneInvalid,
        supportingText = {
          if (showValidation && phoneInvalid) {
            Text(phoneError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_phone_input"),
      )
    }
  }
}

@Composable
private fun AddressEditorDialog(
  editing: AddressDto?,
  isSaving: Boolean,
  error: String?,
  onSave: (
    label: String,
    line1: String,
    line2: String?,
    landmark: String?,
    pincode: String,
    city: String,
    isDefault: Boolean,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  var label by rememberSaveable(editing?.id) { mutableStateOf(editing?.label ?: "Home") }
  var line1 by rememberSaveable(editing?.id) { mutableStateOf(editing?.line1.orEmpty()) }
  var line2 by rememberSaveable(editing?.id) { mutableStateOf(editing?.line2.orEmpty()) }
  var landmark by rememberSaveable(editing?.id) { mutableStateOf(editing?.landmark.orEmpty()) }
  var pincode by rememberSaveable(editing?.id) { mutableStateOf(editing?.pincode.orEmpty()) }
  var city by rememberSaveable(editing?.id) { mutableStateOf(editing?.city ?: "Bengaluru") }
  var isDefault by rememberSaveable(editing?.id) { mutableStateOf(editing?.isDefault ?: false) }
  var showValidation by rememberSaveable(editing?.id) { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val labelRequired = stringResource(R.string.address_error_label_required)
  val line1Required = stringResource(R.string.address_error_line1_required)
  val cityRequired = stringResource(R.string.address_error_city_required)
  val pincodeRequired = stringResource(R.string.address_error_pincode_required)
  val pincodeInvalid = stringResource(R.string.address_error_pincode_invalid)

  val labelError = if (label.trim().isEmpty()) labelRequired else null
  val line1Error = if (line1.trim().isEmpty()) line1Required else null
  val cityError = if (city.trim().isEmpty()) cityRequired else null
  val pincodeError = when {
    pincode.trim().isEmpty() -> pincodeRequired
    !pincode.trim().matches(Regex("^[1-9][0-9]{5}$")) -> pincodeInvalid
    else -> null
  }

  fun submit() {
    showValidation = true
    keyboardController?.hide()
    if (labelError == null && line1Error == null && cityError == null && pincodeError == null && !isSaving) {
      onSave(
        label.trim(),
        line1.trim(),
        line2.takeIf { it.isNotBlank() }?.trim(),
        landmark.takeIf { it.isNotBlank() }?.trim(),
        pincode.trim(),
        city.trim(),
        isDefault,
      )
    }
  }

  val nextFieldActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

  ProfileDialogScaffold(
    title = if (editing == null) stringResource(R.string.address_dialog_title)
    else stringResource(R.string.address_edit_dialog_title),
    onDismiss = onDismiss,
    testTag = "profile_address_dialog",
    confirmButton = {
      Button(
        onClick = ::submit,
        enabled = !isSaving,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_addr_save_button"),
      ) {
        Text(stringResource(R.string.action_save_address))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Text(stringResource(R.string.action_close))
      }
    },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (!error.isNullOrBlank()) {
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("profile_addr_dialog_error"),
        )
      }
      OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text(stringResource(R.string.address_label_hint)) },
        singleLine = true,
        isError = showValidation && labelError != null,
        supportingText = {
          if (showValidation && labelError != null) {
            Text(labelError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = nextFieldActions,
        modifier = Modifier.fillMaxWidth().testTag("profile_addr_label_input"),
      )
      OutlinedTextField(
        value = line1,
        onValueChange = { line1 = it },
        label = { Text(stringResource(R.string.address_line1_hint)) },
        singleLine = true,
        isError = showValidation && line1Error != null,
        supportingText = {
          if (showValidation && line1Error != null) {
            Text(line1Error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = nextFieldActions,
        modifier = Modifier.fillMaxWidth().testTag("profile_addr_line1_input"),
      )
      OutlinedTextField(
        value = line2,
        onValueChange = { line2 = it },
        label = { Text(stringResource(R.string.address_line2_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = nextFieldActions,
        modifier = Modifier.fillMaxWidth().testTag("profile_addr_line2_input"),
      )
      OutlinedTextField(
        value = landmark,
        onValueChange = { landmark = it },
        label = { Text(stringResource(R.string.address_landmark_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = nextFieldActions,
        modifier = Modifier.fillMaxWidth().testTag("profile_addr_landmark_input"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = pincode,
          onValueChange = { v -> pincode = v.filter { it.isDigit() }.take(6) },
          label = { Text(stringResource(R.string.address_pincode_hint)) },
          singleLine = true,
          isError = showValidation && pincodeError != null,
          supportingText = {
            if (showValidation && pincodeError != null) {
              Text(pincodeError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
          keyboardActions = nextFieldActions,
          modifier = Modifier.weight(1f).testTag("profile_addr_pincode_input"),
        )
        OutlinedTextField(
          value = city,
          onValueChange = { city = it },
          label = { Text(stringResource(R.string.address_city_hint)) },
          singleLine = true,
          isError = showValidation && cityError != null,
          supportingText = {
            if (showValidation && cityError != null) {
              Text(cityError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          modifier = Modifier.weight(1f).testTag("profile_addr_city_input"),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.address_set_default_label), style = MaterialTheme.typography.bodyMedium)
        Switch(
          checked = isDefault,
          onCheckedChange = { isDefault = it },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("profile_addr_default_switch"),
        )
      }
    }
  }
}

@Composable
private fun ProfileDialogScaffold(
  title: String,
  onDismiss: () -> Unit,
  testTag: String,
  confirmButton: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding()
        .padding(20.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 560.dp)
          .heightIn(max = 680.dp)
          .testTag(testTag),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(text = title, style = MaterialTheme.typography.headlineSmall)
          content()
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (dismissButton != null) {
              dismissButton()
              Spacer(modifier = Modifier.width(8.dp))
            }
            confirmButton()
          }
        }
      }
    }
  }
}
