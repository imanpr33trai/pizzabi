package com.example.owner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PizzoraMonoBody
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
) {
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

  Box(modifier = modifier.fillMaxSize().testTag("owner_more_screen")) {
    if (uiState.isLoadingMore && settings == null && uiState.staff.isEmpty()) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (uiState.errorMessage != null) {
          item {
            OwnerErrorBanner(message = uiState.errorMessage, onRetry = onRefresh)
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth().testTag("owner_settings_card"),
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Store & GST Configuration", style = MaterialTheme.typography.titleMedium)
              }

              OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("Store Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_store_name_input"),
              )

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                  value = gstBpsInput,
                  onValueChange = { gstBpsInput = it.filter(Char::isDigit) },
                  label = { Text("GST (bps, 500=5%)") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  modifier = Modifier.weight(1f).testTag("settings_gst_bps_input"),
                )
                OutlinedTextField(
                  value = prepMinsInput,
                  onValueChange = { prepMinsInput = it.filter(Char::isDigit) },
                  label = { Text("Prep SLA (mins)") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  modifier = Modifier.weight(1f).testTag("settings_prep_mins_input"),
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("Accepting Orders", style = MaterialTheme.typography.bodyLarge)
                  Text(
                    text = if (acceptingOrders) "Store is open for customer checkout" else "Checkout paused (STORE_CLOSED)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = acceptingOrders,
                  onCheckedChange = { acceptingOrders = it },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_accepting_switch"),
                )
              }

              Button(
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
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("settings_save_button"),
              ) {
                Text("Save Store Settings")
              }
            }
          }
        }

        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text("Kitchen & Owner Staff", style = MaterialTheme.typography.titleMedium)
            }
            Button(
              onClick = { showAddStaffDialog = true },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("add_staff_button"),
            ) {
              Icon(Icons.Filled.Add, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Add Staff")
            }
          }
        }

        if (uiState.staff.isEmpty()) {
          item {
            OwnerEmptyCard(
              title = "No staff accounts yet",
              subtitle = "Provision KITCHEN_STAFF or OWNER accounts here.",
              tag = "owner_staff_empty",
            )
          }
        } else {
          items(uiState.staff, key = { it.userId }) { member ->
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              modifier = Modifier.fillMaxWidth().testTag("staff_card_${member.userId}"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(member.fullName, style = MaterialTheme.typography.titleMedium)
                  Text(
                    text = "${member.role.name} • ${member.employeeCode}",
                    style = PizzoraMonoBody,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = "${member.email} • ${member.phone}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = member.isActive,
                  enabled = !uiState.isSubmitting,
                  onCheckedChange = { onToggleStaffActive(member) },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("staff_active_switch_${member.userId}"),
                )
              }
            }
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

@Composable
private fun AddStaffDialog(
  onDismiss: () -> Unit,
  onCreate: (email: String, password: String, fullName: String, phone: String, role: UserRole, employeeCode: String) -> Unit,
) {
  var fullName by rememberSaveable { mutableStateOf("") }
  var email by rememberSaveable { mutableStateOf("") }
  var phone by rememberSaveable { mutableStateOf("") }
  var employeeCode by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var selectedRole by rememberSaveable { mutableStateOf(UserRole.KITCHEN_STAFF) }

  OwnerModalOverlay(
    title = "Create Staff Account",
    onDismiss = onDismiss,
    confirmButton = {
      Button(
        onClick = { onCreate(email, password, fullName, phone, selectedRole, employeeCode) },
        modifier = Modifier.testTag("staff_submit_button"),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = selectedRole == UserRole.KITCHEN_STAFF,
          onClick = { selectedRole = UserRole.KITCHEN_STAFF },
          label = { Text("KITCHEN_STAFF") },
        )
        FilterChip(
          selected = selectedRole == UserRole.OWNER,
          onClick = { selectedRole = UserRole.OWNER },
          label = { Text("OWNER") },
        )
      }
      OutlinedTextField(
        value = fullName,
        onValueChange = { fullName = it },
        label = { Text("Full Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("staff_name_input"),
      )
      OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("staff_email_input"),
      )
      OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth().testTag("staff_phone_input"),
      )
      OutlinedTextField(
        value = employeeCode,
        onValueChange = { employeeCode = it },
        label = { Text("Employee Code (e.g. CHEF-02)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("staff_code_input"),
      )
      OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Temporary Password (8+ chars)") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("staff_password_input"),
      )
    }
  }
}
