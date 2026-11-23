package com.example.owner

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.R
import com.example.ui.components.PizzoraButton
import com.example.ui.components.PizzoraChip
import com.example.ui.components.PizzoraChipVariant
import com.example.ui.components.PizzoraEmptyState
import com.example.ui.components.PizzoraListItem
import com.example.ui.components.PizzoraProgressIndicator
import com.example.ui.components.PizzoraProgressIndicatorStyle
import com.example.ui.components.PizzoraSection
import com.example.ui.components.PizzoraSurface
import com.example.ui.components.PizzoraSurfaceTone
import com.example.ui.components.PizzoraTextButton
import com.example.ui.components.PizzoraTextField
import com.example.ui.theme.LocalPizzoraTokens
import com.example.ui.theme.PizzoraMonoBody
import com.example.update.AppUpdatesSettingsCard
import com.example.update.UpdateUiState
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UserRole

@Composable
fun OwnerSettingsAndStaffScreen(
  uiState: OwnerUiState,
  onRefresh: () -> Unit,
  onUpdateSettings: (UpdateRestaurantSettingsRequest) -> Unit,
  onCreateStaff: (email: String, password: String, fullName: String, phone: String, role: UserRole, employeeCode: String) -> Unit,
  onToggleStaffActive: (StaffMemberDto) -> Unit,
  modifier: Modifier = Modifier,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  val tokens = LocalPizzoraTokens.current
  val settings = uiState.settings ?: uiState.dashboard?.settings
  var storeName by rememberSaveable { mutableStateOf(settings?.storeName ?: "PIZZORA Artisanal Kitchen") }
  var gstBpsInput by rememberSaveable { mutableStateOf((settings?.gstBps ?: 500).toString()) }
  var prepMinsInput by rememberSaveable { mutableStateOf((settings?.defaultPrepMins ?: 20).toString()) }
  var acceptingOrders by rememberSaveable { mutableStateOf(settings?.isAcceptingOrders ?: true) }
  var showAddStaffDialog by remember { mutableStateOf(false) }

  LaunchedEffect(settings?.updatedAt) {
    if (settings != null) {
      storeName = settings.storeName
      gstBpsInput = settings.gstBps.toString()
      prepMinsInput = settings.defaultPrepMins.toString()
      acceptingOrders = settings.isAcceptingOrders
    }
  }

  PizzoraSurface(
    modifier = modifier.fillMaxSize(),
    tone = PizzoraSurfaceTone.Background,
    testTag = "owner_more_screen",
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      if (uiState.isLoadingMore && settings == null && uiState.staff.isEmpty()) {
        PizzoraProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
          style = PizzoraProgressIndicatorStyle.Circular,
          testTag = "owner_more_loading_indicator",
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(tokens.spacing.space16),
          verticalArrangement = Arrangement.spacedBy(tokens.spacing.space16),
        ) {
          if (uiState.errorMessage != null) {
            item(key = "owner_settings_error_banner") {
              SettingsErrorBanner(message = uiState.errorMessage, onRetry = onRefresh)
            }
          }

          item(key = "owner_settings_card") {
            PizzoraSection(
              title = stringResource(R.string.settings_store_title),
              testTag = "owner_settings_card",
            ) {
              PizzoraTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = stringResource(R.string.settings_store_name_label),
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings_store_name_input",
              )

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space12)) {
                PizzoraTextField(
                  value = gstBpsInput,
                  onValueChange = { gstBpsInput = it.filter(Char::isDigit) },
                  label = stringResource(R.string.settings_gst_bps_label),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  testTag = "settings_gst_bps_input",
                )
                PizzoraTextField(
                  value = prepMinsInput,
                  onValueChange = { prepMinsInput = it.filter(Char::isDigit) },
                  label = stringResource(R.string.settings_prep_mins_label),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  testTag = "settings_prep_mins_input",
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth().testTag("settings_accepting_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(R.string.settings_accepting_orders_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("settings_accepting_row_title"),
                  )
                  Text(
                    text = stringResource(
                      if (acceptingOrders) R.string.settings_accepting_orders_open else R.string.settings_accepting_orders_paused,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_accepting_row_subtitle"),
                  )
                }
                Switch(
                  checked = acceptingOrders,
                  onCheckedChange = { acceptingOrders = it },
                  modifier = Modifier
                    .defaultMinSize(
                      minWidth = tokens.sizing.touchTargetMin,
                      minHeight = tokens.sizing.touchTargetMin,
                    )
                    .testTag("settings_accepting_switch"),
                )
              }

              PizzoraButton(
                text = stringResource(R.string.settings_save_button),
                onClick = {
                  onUpdateSettings(
                    UpdateRestaurantSettingsRequest(
                      storeName = storeName.trim(),
                      isAcceptingOrders = acceptingOrders,
                      gstBps = gstBpsInput.toIntOrNull(),
                      defaultPrepMins = prepMinsInput.toIntOrNull(),
                    ),
                  )
                },
                enabled = !uiState.isSubmitting,
                loading = uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings_save_button",
              )
            }
          }

          if (updateUiState != null) {
            item(key = "owner_app_updates_card") {
              AppUpdatesSettingsCard(
                uiState = updateUiState,
                onCheckForUpdates = onCheckForUpdates,
                onOpenUpdateDialog = onOpenUpdateDialog,
                onRetry = onRetryUpdate,
              )
            }
          }

          item(key = "owner_staff_header") {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_staff_title), style = MaterialTheme.typography.titleMedium)
              }
              PizzoraButton(
                text = stringResource(R.string.settings_add_staff),
                onClick = { showAddStaffDialog = true },
                leadingIcon = Icons.Filled.Add,
                testTag = "add_staff_button",
              )
            }
          }

          if (uiState.staff.isEmpty()) {
            item(key = "owner_staff_empty") {
              PizzoraEmptyState(
                title = stringResource(R.string.settings_staff_empty_title),
                subtitle = stringResource(R.string.settings_staff_empty_subtitle),
                illustration = Icons.Filled.Badge,
                testTag = "owner_staff_empty",
              )
            }
          } else {
            items(uiState.staff, key = { it.userId }) { member ->
              PizzoraListItem(
                title = member.fullName,
                subtitle = "${member.email} • ${member.phone}",
                leadingIcon = Icons.Filled.Person,
                trailing = {
                  Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.space4),
                  ) {
                    Text(
                      text = "${member.role.name} • ${member.employeeCode}",
                      style = PizzoraMonoBody,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Switch(
                      checked = member.isActive,
                      enabled = !uiState.isSubmitting,
                      onCheckedChange = { onToggleStaffActive(member) },
                      modifier = Modifier
                        .defaultMinSize(
                          minWidth = tokens.sizing.touchTargetMin,
                          minHeight = tokens.sizing.touchTargetMin,
                        )
                        .testTag("staff_active_switch_${member.userId}"),
                    )
                  }
                },
                testTag = "staff_card_${member.userId}",
              )
            }
          }
        }
      }

      if (showAddStaffDialog) {
        AddStaffDialog(
          onDismiss = { showAddStaffDialog = false },
          onCreate = { email, password, fullName, phone, role, employeeCode ->
            onCreateStaff(email, password, fullName, phone, role, employeeCode)
            showAddStaffDialog = false
          },
        )
      }
    }
  }
}

@Composable
private fun SettingsErrorBanner(
  message: String,
  onRetry: () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  PizzoraSurface(
    tone = PizzoraSurfaceTone.ErrorContainer,
    shape = RoundedCornerShape(tokens.shape.radiusMedium),
    modifier = Modifier.fillMaxWidth(),
    testTag = "owner_error_banner",
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(tokens.spacing.space12),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      PizzoraTextButton(
        text = stringResource(R.string.action_retry),
        onClick = onRetry,
        testTag = "owner_error_retry",
      )
    }
  }
}

@Composable
private fun AddStaffDialog(
  onDismiss: () -> Unit,
  onCreate: (email: String, password: String, fullName: String, phone: String, role: UserRole, employeeCode: String) -> Unit,
) {
  val tokens = LocalPizzoraTokens.current
  var fullName by rememberSaveable { mutableStateOf("") }
  var email by rememberSaveable { mutableStateOf("") }
  var phone by rememberSaveable { mutableStateOf("") }
  var employeeCode by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var selectedRole by rememberSaveable { mutableStateOf(UserRole.KITCHEN_STAFF) }

  SettingsModalOverlay(
    title = stringResource(R.string.staff_dialog_title),
    onDismiss = onDismiss,
    confirmButton = {
      PizzoraButton(
        text = stringResource(R.string.staff_create_button),
        onClick = { onCreate(email, password, fullName, phone, selectedRole, employeeCode) },
        testTag = "staff_submit_button",
      )
    },
    dismissButton = {
      PizzoraTextButton(
        text = stringResource(R.string.staff_cancel_button),
        onClick = onDismiss,
        testTag = "staff_cancel_button",
      )
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.space8)) {
      Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8)) {
        PizzoraChip(
          label = UserRole.KITCHEN_STAFF.name,
          onClick = { selectedRole = UserRole.KITCHEN_STAFF },
          variant = PizzoraChipVariant.Filter,
          selected = selectedRole == UserRole.KITCHEN_STAFF,
          testTag = "staff_role_chip_kitchen",
        )
        PizzoraChip(
          label = UserRole.OWNER.name,
          onClick = { selectedRole = UserRole.OWNER },
          variant = PizzoraChipVariant.Filter,
          selected = selectedRole == UserRole.OWNER,
          testTag = "staff_role_chip_owner",
        )
      }
      PizzoraTextField(
        value = fullName,
        onValueChange = { fullName = it },
        label = stringResource(R.string.label_full_name),
        modifier = Modifier.fillMaxWidth(),
        testTag = "staff_name_input",
      )
      PizzoraTextField(
        value = email,
        onValueChange = { email = it },
        label = stringResource(R.string.label_email),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
        testTag = "staff_email_input",
      )
      PizzoraTextField(
        value = phone,
        onValueChange = { phone = it },
        label = stringResource(R.string.staff_phone_label),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
        testTag = "staff_phone_input",
      )
      PizzoraTextField(
        value = employeeCode,
        onValueChange = { employeeCode = it },
        label = stringResource(R.string.staff_code_label),
        modifier = Modifier.fillMaxWidth(),
        testTag = "staff_code_input",
      )
      PizzoraTextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.staff_password_label),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        testTag = "staff_password_input",
      )
    }
  }
}

@Composable
private fun SettingsModalOverlay(
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
