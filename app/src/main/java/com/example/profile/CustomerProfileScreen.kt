package com.example.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.example.R
import com.example.ui.components.PizzoraBadge
import com.example.ui.components.PizzoraBadgeTone
import com.example.ui.components.PizzoraButton
import com.example.ui.components.PizzoraCard
import com.example.ui.components.PizzoraEmptyState
import com.example.ui.components.PizzoraIconButton
import com.example.ui.components.PizzoraListItem
import com.example.ui.components.PizzoraOutlinedButton
import com.example.ui.components.PizzoraProgressIndicator
import com.example.ui.components.PizzoraSection
import com.example.ui.components.PizzoraSurface
import com.example.ui.components.PizzoraSurfaceTone
import com.example.ui.components.PizzoraTextButton
import com.example.ui.components.PizzoraTextField
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraMonoBody
import com.example.update.AppUpdatesSettingsCard
import com.example.update.UpdateUiState
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.UserProfileDto

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
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  val tokens = LocalPizzoraTokens.current
  Box(modifier = modifier.fillMaxSize()) {
    PizzoraSurface(
      modifier = Modifier.fillMaxSize(),
      tone = PizzoraSurfaceTone.Background,
      testTag = "customer_profile_screen",
    ) {
      if (uiState.isLoading || uiState.isSavingAddress || uiState.isSavingProfile) {
        PizzoraProgressIndicator(
          modifier = Modifier.fillMaxWidth(),
          testTag = "profile_loading_indicator",
        )
      }

      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .widthIn(max = tokens.sizing.listMaxWidth)
          .align(Alignment.CenterHorizontally)
          .testTag("profile_lazy_column"),
        contentPadding = PaddingValues(tokens.spacing.space16),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.space12),
      ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
          item(key = "profile_error_banner") {
            PizzoraSurface(
              tone = PizzoraSurfaceTone.ErrorContainer,
              shape = RoundedCornerShape(tokens.shape.radiusMedium),
              modifier = Modifier.fillMaxWidth(),
              testTag = "profile_error_banner",
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(tokens.spacing.space12),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = uiState.errorMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.weight(1f),
                )
                PizzoraTextButton(
                  text = stringResource(R.string.action_retry),
                  onClick = onRefresh,
                  testTag = "profile_error_retry",
                )
              }
            }
          }
        }

        item(key = "profile_account_card") {
          AccountProfileCard(
            profile = uiState.profile,
            onEdit = onOpenEditProfile,
            onSignOut = onSignOut,
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
              horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
            ) {
              Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text(
                text = stringResource(R.string.profile_saved_addresses_title, uiState.addresses.size),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            PizzoraButton(
              text = stringResource(R.string.action_add_address),
              onClick = onOpenAddAddress,
              leadingIcon = Icons.Filled.Add,
              testTag = "profile_add_address_button",
            )
          }
        }

        if (uiState.addresses.isEmpty()) {
          item(key = "profile_addresses_empty") {
            PizzoraEmptyState(
              title = stringResource(R.string.profile_no_addresses_title),
              subtitle = stringResource(R.string.profile_no_addresses_subtitle),
              illustration = Icons.Filled.LocationOn,
              testTag = "profile_addresses_empty",
            )
          }
        } else {
          items(uiState.addresses, key = { it.id }) { address ->
            SavedAddressItem(
              address = address,
              isDeleting = uiState.deletingAddressId == address.id,
              onSetDefault = { onSetDefaultAddress(address) },
              onEdit = { onOpenEditAddress(address) },
              onDelete = { onDeleteAddress(address.id) },
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

        item(key = "profile_bottom_spacer") {
          Spacer(modifier = Modifier.height(tokens.spacing.space72).testTag("profile_bottom_spacer"))
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
  }
}

@Composable
private fun AccountProfileCard(
  profile: UserProfileDto?,
  onEdit: () -> Unit,
  onSignOut: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraCard(
    modifier = Modifier.fillMaxWidth(),
    testTag = "profile_account_card",
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.space12),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space12),
          modifier = Modifier.weight(1f),
        ) {
          PizzoraSurface(
            tone = PizzoraSurfaceTone.PrimaryContainer,
            shape = RoundedCornerShape(tokens.shape.radiusMedium),
            modifier = Modifier.size(tokens.sizing.avatarSize),
          ) {
            Icon(
              imageVector = Icons.Filled.Person,
              contentDescription = null,
              modifier = Modifier
                .padding(tokens.spacing.space12)
                .size(tokens.sizing.iconMedium),
            )
          }
          Column {
            Text(
              text = profile?.fullName ?: stringResource(R.string.profile_loading_name),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.testTag("profile_full_name_text"),
            )
            Text(
              text = profile?.email.orEmpty(),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        PizzoraOutlinedButton(
          text = stringResource(R.string.action_edit),
          onClick = onEdit,
          leadingIcon = Icons.Filled.Edit,
          testTag = "edit_profile_button",
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.profile_phone_fmt, profile?.phone.orEmpty()),
          style = PizzoraMonoBody,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("profile_phone_text"),
        )
        PizzoraTextButton(
          text = stringResource(R.string.action_sign_out),
          onClick = onSignOut,
          testTag = "profile_sign_out_button",
        )
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
  val tokens = LocalPizzoraTokens.current
  PizzoraSection(
    title = stringResource(R.string.profile_pincode_checker_title),
    testTag = "profile_pincode_card",
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PizzoraTextField(
        value = uiState.pincodeCheckInput,
        onValueChange = onPincodeInputChange,
        label = stringResource(R.string.pincode_label),
        modifier = Modifier.weight(1f),
        testTag = "profile_pincode_input",
      )
      PizzoraButton(
        text = stringResource(R.string.action_check),
        onClick = onCheckPincode,
        enabled = !uiState.isCheckingPincode,
        loading = uiState.isCheckingPincode,
        leadingIcon = Icons.Filled.Search,
        testTag = "profile_check_pincode_button",
      )
    }

    if (!uiState.pincodeCheckError.isNullOrBlank()) {
      Text(
        text = uiState.pincodeCheckError,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("profile_pincode_error_text"),
      )
    }

    uiState.pincodeCheckResult?.let { result ->
      PizzoraSurface(
        tone = if (result.isServiceable) {
          PizzoraSurfaceTone.SecondaryContainer
        } else {
          PizzoraSurfaceTone.ErrorContainer
        },
        shape = RoundedCornerShape(tokens.shape.radiusSmall),
        modifier = Modifier
          .fillMaxWidth()
          .semantics(mergeDescendants = true) {},
        testTag = "profile_pincode_result_banner",
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(tokens.spacing.space12),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
        ) {
          Icon(
            imageVector = if (result.isServiceable) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = result.message,
              style = MaterialTheme.typography.bodyMedium,
            )
            result.zone?.let { zone ->
              Text(
                text = stringResource(
                  R.string.profile_pincode_zone_fmt,
                  Money.formatInr(zone.minOrderPaise),
                  Money.formatInr(zone.deliveryFeePaise),
                  zone.estimatedMins,
                ),
                style = PizzoraMonoBody,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SavedAddressItem(
  address: AddressDto,
  isDeleting: Boolean,
  onSetDefault: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  val details = buildString {
    append(address.line1)
    if (!address.line2.isNullOrBlank()) append(", ${address.line2}")
    if (!address.landmark.isNullOrBlank()) append(" (${address.landmark})")
    append(", ${address.city} - ${address.pincode}")
  }
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
  ) {
    PizzoraListItem(
      title = address.label,
      subtitle = details,
      leadingIcon = Icons.Filled.LocationOn,
      trailing = {
        Row(
          horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space4),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (address.isDefault) {
            PizzoraBadge(
              label = stringResource(R.string.address_default_badge),
              tone = PizzoraBadgeTone.Info,
              testTag = "profile_default_badge_${address.id}",
            )
          }
          PizzoraIconButton(
            icon = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.action_edit),
            onClick = onEdit,
            testTag = "profile_edit_address_${address.id}",
          )
          PizzoraIconButton(
            icon = Icons.Filled.DeleteOutline,
            contentDescription = stringResource(R.string.action_remove),
            onClick = onDelete,
            enabled = !isDeleting,
            testTag = "profile_delete_address_${address.id}",
          )
        }
      },
      testTag = "profile_address_card_${address.id}",
    )
    if (!address.isDefault) {
      PizzoraOutlinedButton(
        text = stringResource(R.string.action_set_default),
        onClick = onSetDefault,
        modifier = Modifier.padding(horizontal = tokens.spacing.space16),
        testTag = "profile_set_default_${address.id}",
      )
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
  val tokens = LocalPizzoraTokens.current
  var fullName by remember(profile?.id) { mutableStateOf(profile?.fullName.orEmpty()) }
  var phone by remember(profile?.id) { mutableStateOf(profile?.phone.orEmpty()) }

  ProfileModalOverlay(
    title = stringResource(R.string.profile_edit_title),
    onDismiss = onDismiss,
    confirmButton = {
      PizzoraButton(
        text = stringResource(R.string.action_save),
        onClick = { onSave(fullName, phone) },
        enabled = !isSaving,
        loading = isSaving,
        testTag = "profile_edit_save_button",
      )
    },
    dismissButton = {
      PizzoraOutlinedButton(
        text = stringResource(R.string.action_close),
        onClick = onDismiss,
        testTag = "profile_edit_cancel_button",
      )
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8)) {
      if (!error.isNullOrBlank()) {
        Text(
          text = error,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("profile_edit_dialog_error"),
        )
      }
      PizzoraTextField(
        value = fullName,
        onValueChange = { fullName = it },
        label = stringResource(R.string.label_full_name),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_edit_name_input",
      )
      PizzoraTextField(
        value = phone,
        onValueChange = { phone = it },
        label = stringResource(R.string.label_phone),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_edit_phone_input",
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
  val tokens = LocalPizzoraTokens.current
  var label by remember(editing?.id) { mutableStateOf(editing?.label ?: "Home") }
  var line1 by remember(editing?.id) { mutableStateOf(editing?.line1.orEmpty()) }
  var line2 by remember(editing?.id) { mutableStateOf(editing?.line2.orEmpty()) }
  var landmark by remember(editing?.id) { mutableStateOf(editing?.landmark.orEmpty()) }
  var pincode by remember(editing?.id) { mutableStateOf(editing?.pincode.orEmpty()) }
  var city by remember(editing?.id) { mutableStateOf(editing?.city ?: "Bengaluru") }
  var isDefault by remember(editing?.id) { mutableStateOf(editing?.isDefault ?: false) }

  ProfileModalOverlay(
    title = if (editing == null) stringResource(R.string.address_dialog_title)
    else stringResource(R.string.address_edit_dialog_title),
    onDismiss = onDismiss,
    confirmButton = {
      PizzoraButton(
        text = stringResource(R.string.action_save_address),
        onClick = { onSave(label, line1, line2, landmark, pincode, city, isDefault) },
        enabled = !isSaving,
        loading = isSaving,
        testTag = "profile_addr_save_button",
      )
    },
    dismissButton = {
      PizzoraOutlinedButton(
        text = stringResource(R.string.action_close),
        onClick = onDismiss,
        testTag = "profile_addr_cancel_button",
      )
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = tokens.sizing.dialogContentMaxHeight)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
    ) {
      if (!error.isNullOrBlank()) {
        Text(
          text = error,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("profile_addr_dialog_error"),
        )
      }
      PizzoraTextField(
        value = label,
        onValueChange = { label = it },
        label = stringResource(R.string.address_label_hint),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_addr_label_input",
      )
      PizzoraTextField(
        value = line1,
        onValueChange = { line1 = it },
        label = stringResource(R.string.address_line1_hint),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_addr_line1_input",
      )
      PizzoraTextField(
        value = line2,
        onValueChange = { line2 = it },
        label = stringResource(R.string.address_line2_hint),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_addr_line2_input",
      )
      PizzoraTextField(
        value = landmark,
        onValueChange = { landmark = it },
        label = stringResource(R.string.address_landmark_hint),
        modifier = Modifier.fillMaxWidth(),
        testTag = "profile_addr_landmark_input",
      )
      Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8)) {
        PizzoraTextField(
          value = pincode,
          onValueChange = { v -> pincode = v.filter { it.isDigit() }.take(6) },
          label = stringResource(R.string.address_pincode_hint),
          modifier = Modifier.weight(1f),
          testTag = "profile_addr_pincode_input",
        )
        PizzoraTextField(
          value = city,
          onValueChange = { city = it },
          label = stringResource(R.string.address_city_hint),
          modifier = Modifier.weight(1f),
          testTag = "profile_addr_city_input",
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
          modifier = Modifier
            .defaultMinSize(
              minWidth = tokens.sizing.touchTargetMin,
              minHeight = tokens.sizing.touchTargetMin,
            )
            .testTag("profile_addr_default_switch"),
        )
      }
    }
  }
}

@Composable
private fun ProfileModalOverlay(
  title: String,
  onDismiss: () -> Unit,
  confirmButton: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  BackHandler(onBack = onDismiss)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss,
      ),
    contentAlignment = Alignment.Center,
  ) {
    PizzoraSurface(
      tone = PizzoraSurfaceTone.Surface,
      shape = RoundedCornerShape(tokens.shape.radiusLarge),
      elevation = tokens.elevation.modal,
      modifier = Modifier
        .padding(tokens.spacing.space20)
        .fillMaxWidth()
        .widthIn(max = tokens.sizing.contentMaxWidth)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    ) {
      Column(
        modifier = Modifier.padding(tokens.spacing.space24),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.space16),
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
            Spacer(modifier = Modifier.width(tokens.spacing.space8))
          }
          confirmButton()
        }
      }
    }
  }
}
