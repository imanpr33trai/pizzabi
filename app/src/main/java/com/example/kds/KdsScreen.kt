package com.example.kds

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalPizzoraSemanticColors
import com.example.ui.theme.PizzoraMonoBody
import com.example.ui.theme.PizzoraMonoTitle
import com.example.ui.theme.PizzoraTheme
import com.example.update.AppUpdatesSettingsCard
import com.example.update.UpdateUiState
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsTicketDto
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.RealtimeConnectionState
import com.pizzora.contracts.ScheduleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdsScreen(
  uiState: KdsUiState,
  operatorName: String,
  onSelectFilter: (KdsFilterTab) -> Unit,
  onToggleIncludeCompleted: () -> Unit,
  onToggleMute: () -> Unit,
  onRefresh: () -> Unit,
  onAdvanceStage: (KdsTicketDto) -> Unit,
  onTogglePriority: (KdsTicketDto) -> Unit,
  onSignOut: (() -> Unit)? = null,
  onBackClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  updateUiState: UpdateUiState? = null,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  onRetryUpdate: () -> Unit = {},
) {
  var showKdsUpdatesSheet by remember { mutableStateOf(false) }
  val context = LocalContext.current
  // Play-policy compliant active-screen KDS keep-awake (avoids background dataSync FGS limits on Android 14-16)
  DisposableEffect(context) {
    val activity = context.findActivity()
    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose {
      activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  PizzoraTheme(kdsMode = true) {
    val semantic = LocalPizzoraSemanticColors.current
    val visibleTickets = remember(uiState.tickets, uiState.selectedFilter, uiState.includeCompleted) {
      uiState.visibleTickets
    }

    Scaffold(
      modifier = modifier.fillMaxSize().testTag("kds_board_root"),
      contentWindowInsets = WindowInsets.safeDrawing,
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        TopAppBar(
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
          navigationIcon = {
            if (onBackClick != null) {
              IconButton(
                onClick = onBackClick,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("kds_back_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = stringResource(R.string.action_back),
                )
              }
            }
          },
          title = {
            Column {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Kitchen,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = stringResource(R.string.kds_board_title),
                  style = MaterialTheme.typography.titleLarge,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shape = MaterialTheme.shapes.small,
                ) {
                  Text(
                    text = "${uiState.activeTicketsCount}",
                    style = PizzoraMonoBody,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag("kds_active_count"),
                  )
                }
                if (uiState.priorityCount > 0) {
                  Surface(
                    color = semantic.kdsPendingAmber.copy(alpha = 0.22f),
                    shape = MaterialTheme.shapes.small,
                  ) {
                    Text(
                      text = "${uiState.priorityCount} RUSH",
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                      color = semantic.kdsPendingAmber,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag("kds_priority_count"),
                    )
                  }
                }
              }
              Text(
                text = "$operatorName • SLA ${uiState.prepSlaMinutes}m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          },
          actions = {
            KdsConnectionPill(connectionState = uiState.connectionState)
            IconButton(
              onClick = onToggleMute,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("kds_mute_button"),
            ) {
              Icon(
                imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (uiState.isMuted) {
                  stringResource(R.string.kds_unmute_chimes)
                } else {
                  stringResource(R.string.kds_mute_chimes)
                },
                tint = if (uiState.isMuted) semantic.kdsOverdueCrimson else MaterialTheme.colorScheme.onSurface,
              )
            }
            IconButton(
              onClick = onRefresh,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("kds_refresh_button"),
            ) {
              Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            if (updateUiState != null) {
              IconButton(
                onClick = { showKdsUpdatesSheet = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("kds_update_button"),
              ) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = stringResource(R.string.update_kds_cd))
              }
            }
            if (onSignOut != null) {
              IconButton(
                onClick = onSignOut,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("shell_sign_out_button"),
              ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.action_sign_out))
              }
            }
          },
        )
      },
    ) { innerPadding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (uiState.isLoading) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Status filter strip + Completed toggle
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          KdsFilterTab.entries.forEach { tab ->
            val label = when (tab) {
              KdsFilterTab.ALL -> "All"
              KdsFilterTab.NEW -> "New"
              KdsFilterTab.ACCEPTED -> "Accepted"
              KdsFilterTab.BAKING -> "Baking"
              KdsFilterTab.READY -> "Ready"
              KdsFilterTab.DISPATCH -> "Dispatch"
            }
            val count = uiState.countFor(tab)
            FilterChip(
              selected = uiState.selectedFilter == tab,
              onClick = { onSelectFilter(tab) },
              label = { Text("$label ($count)") },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
              modifier = Modifier.heightIn(min = 40.dp).testTag("kds_filter_${tab.name.lowercase()}"),
            )
          }

          FilterChip(
            selected = uiState.includeCompleted,
            onClick = onToggleIncludeCompleted,
            label = { Text(stringResource(R.string.kds_show_completed)) },
            leadingIcon = if (uiState.includeCompleted) {
              { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
              null
            },
            modifier = Modifier.heightIn(min = 40.dp).testTag("kds_toggle_completed"),
          )
        }

        if (!uiState.errorMessage.isNullOrBlank()) {
          Surface(
            color = semantic.kdsOverdueCrimson.copy(alpha = 0.16f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("kds_error_banner"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = uiState.errorMessage,
              color = semantic.kdsOverdueCrimson,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(12.dp),
            )
          }
        }

        if (visibleTickets.isEmpty() && !uiState.isLoading) {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag("kds_empty_state"),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
              )
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = stringResource(R.string.kds_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = stringResource(R.string.kds_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
          ) {
            val isWideBoard = maxWidth >= 600.dp
            val topTicketId = visibleTickets.firstOrNull()?.id
            if (isWideBoard) {
              val gridState = rememberLazyGridState()
              var previousTopId by remember { mutableStateOf(topTicketId) }
              var scrollToTopTargetId by remember { mutableStateOf<String?>(null) }
              SideEffect {
                if (topTicketId != previousTopId) {
                  val wasAtTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                  previousTopId = topTicketId
                  if (wasAtTop && topTicketId != null) {
                    scrollToTopTargetId = topTicketId
                  }
                }
              }
              LaunchedEffect(scrollToTopTargetId, gridState.firstVisibleItemIndex) {
                if (scrollToTopTargetId != null && scrollToTopTargetId == topTicketId) {
                  if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                    gridState.scrollToItem(0)
                    scrollToTopTargetId = null
                  }
                }
              }
              LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 320.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().testTag("kds_tickets_grid"),
              ) {
                items(visibleTickets, key = { it.id }) { ticket ->
                  KdsTicketCard(
                    ticket = ticket,
                    serverAdjustedNowMs = uiState.serverAdjustedNowMs,
                    prepSlaMinutes = uiState.prepSlaMinutes,
                    isInFlight = ticket.id in uiState.inFlightTicketIds,
                    onAdvanceStage = { onAdvanceStage(ticket) },
                    onTogglePriority = { onTogglePriority(ticket) },
                  )
                }
              }
            } else {
              val listState = rememberLazyListState()
              var previousTopId by remember { mutableStateOf(topTicketId) }
              var scrollToTopTargetId by remember { mutableStateOf<String?>(null) }
              SideEffect {
                if (topTicketId != previousTopId) {
                  val wasAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                  previousTopId = topTicketId
                  if (wasAtTop && topTicketId != null) {
                    scrollToTopTargetId = topTicketId
                  }
                }
              }
              LaunchedEffect(scrollToTopTargetId, listState.firstVisibleItemIndex) {
                if (scrollToTopTargetId != null && scrollToTopTargetId == topTicketId) {
                  if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                    listState.scrollToItem(0)
                    scrollToTopTargetId = null
                  }
                }
              }
              LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().testTag("kds_tickets_list"),
              ) {
                items(visibleTickets, key = { it.id }) { ticket ->
                  KdsTicketCard(
                    ticket = ticket,
                    serverAdjustedNowMs = uiState.serverAdjustedNowMs,
                    prepSlaMinutes = uiState.prepSlaMinutes,
                    isInFlight = ticket.id in uiState.inFlightTicketIds,
                    onAdvanceStage = { onAdvanceStage(ticket) },
                    onTogglePriority = { onTogglePriority(ticket) },
                  )
                }
              }
            }
          }
        }
      }
    }

    if (showKdsUpdatesSheet && updateUiState != null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
          .padding(20.dp)
          .testTag("kds_updates_dialog"),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.End,
        ) {
          AppUpdatesSettingsCard(
            uiState = updateUiState,
            onCheckForUpdates = onCheckForUpdates,
            onOpenUpdateDialog = {
              showKdsUpdatesSheet = false
              onOpenUpdateDialog()
            },
            onRetry = onRetryUpdate,
          )
          Button(onClick = { showKdsUpdatesSheet = false }) {
            Text(stringResource(R.string.action_close))
          }
        }
      }
    }
  }
}

@Composable
private fun KdsTicketCard(
  ticket: KdsTicketDto,
  serverAdjustedNowMs: Long,
  prepSlaMinutes: Int,
  isInFlight: Boolean,
  onAdvanceStage: () -> Unit,
  onTogglePriority: () -> Unit,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val createdMs = remember(ticket.createdAt) { parseIso8601UtcMillis(ticket.createdAt) }
  val elapsedMs = if (createdMs != null) (serverAdjustedNowMs - createdMs).coerceAtLeast(0L) else 0L
  val slaThresholdMs = prepSlaMinutes.coerceAtLeast(1) * 60_000L
  val isOverdue = !ticket.status.isTerminal && createdMs != null && elapsedMs >= slaThresholdMs

  val accentColor = when {
    isOverdue -> semantic.kdsOverdueCrimson
    ticket.status == OrderStatus.PLACED -> semantic.kdsPendingAmber
    ticket.status == OrderStatus.ACCEPTED || ticket.status == OrderStatus.PREPARING -> semantic.kdsPrepSky
    else -> semantic.kdsReadyEmerald
  }

  val ctaLabel = when (ticket.status) {
    OrderStatus.PLACED -> stringResource(R.string.kds_action_ack)
    OrderStatus.ACCEPTED -> stringResource(R.string.kds_action_start_prep)
    OrderStatus.PREPARING -> stringResource(R.string.kds_action_ready)
    OrderStatus.READY -> if (ticket.fulfillmentType == FulfillmentType.DELIVERY) {
      stringResource(R.string.kds_action_dispatch)
    } else {
      stringResource(R.string.kds_action_complete)
    }
    OrderStatus.OUT_FOR_DELIVERY -> stringResource(R.string.kds_action_complete_delivery)
    else -> null
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(
      width = if (ticket.isPriority || isOverdue) 2.dp else 1.dp,
      color = if (ticket.isPriority && !isOverdue) semantic.kdsPendingAmber else accentColor.copy(alpha = 0.7f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("kds_ticket_card_${ticket.id}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // High-contrast color status bar
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp)
          .background(accentColor),
      )

      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Header: Order Number + Priority + SLA Clock
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = ticket.orderNumber,
                style = PizzoraMonoTitle,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (ticket.isPriority) {
                Surface(
                  color = semantic.kdsPendingAmber.copy(alpha = 0.22f),
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.testTag("kds_priority_badge_${ticket.id}"),
                ) {
                  Text(
                    text = stringResource(R.string.kds_priority_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = semantic.kdsPendingAmber,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
              if (isOverdue) {
                Surface(
                  color = semantic.kdsOverdueCrimson.copy(alpha = 0.22f),
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.testTag("kds_overdue_badge_${ticket.id}"),
                ) {
                  Text(
                    text = stringResource(R.string.kds_overdue_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = semantic.kdsOverdueCrimson,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = ticket.status.name.replace('_', ' '),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = accentColor,
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Surface(
              color = accentColor.copy(alpha = 0.16f),
              shape = MaterialTheme.shapes.small,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Schedule,
                  contentDescription = null,
                  tint = accentColor,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = formatElapsedClock(elapsedMs),
                  style = PizzoraMonoTitle,
                  color = accentColor,
                  modifier = Modifier.testTag("kds_ticket_timer_${ticket.id}"),
                )
              }
            }

            if (!ticket.status.isTerminal) {
              IconButton(
                onClick = onTogglePriority,
                enabled = !isInFlight,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("kds_priority_button_${ticket.id}"),
              ) {
                Icon(
                  imageVector = if (ticket.isPriority) Icons.Filled.Bolt else Icons.Outlined.Bolt,
                  contentDescription = stringResource(R.string.kds_toggle_priority),
                  tint = if (ticket.isPriority) semantic.kdsPendingAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        // Fulfillment & Customer metadata
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Icon(
                imageVector = if (ticket.fulfillmentType == FulfillmentType.DELIVERY) {
                  Icons.Filled.DeliveryDining
                } else {
                  Icons.Filled.Storefront
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = ticket.fulfillmentType.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          if (ticket.scheduleType == ScheduleType.SCHEDULED) {
            Surface(
              color = MaterialTheme.colorScheme.tertiaryContainer,
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = "SCHEDULED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          val paymentTag = when {
            ticket.paymentMethod == PaymentMethod.COD && ticket.paymentStatus == PaymentStatus.COD_COLLECTED -> "COD COLLECTED"
            ticket.paymentMethod == PaymentMethod.COD -> "COD ${Money.formatInr(ticket.totalPaise)}"
            else -> "PAID"
          }
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = paymentTag,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }

        Text(
          text = buildString {
            append(ticket.customerName)
            ticket.deliveryArea?.let {
              append(" • ")
              append(it)
            }
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Itemized Pizza Prep Lines
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ticket.items.forEach { item ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
              ) {
                Text(
                  text = "${item.quantity}x",
                  style = PizzoraMonoBody,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.productName,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "${item.variantSize} • ${item.crustName}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.toppings.isNotEmpty()) {
                  Text(
                    text = "+ ${item.toppings.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }

        if (ctaLabel != null) {
          Spacer(modifier = Modifier.height(2.dp))
          Button(
            onClick = onAdvanceStage,
            enabled = !isInFlight,
            colors = ButtonDefaults.buttonColors(
              containerColor = accentColor,
              contentColor = Color(0xFF121110),
            ),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("kds_primary_action_${ticket.id}"),
          ) {
            if (isInFlight) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF121110),
              )
              Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
              text = ctaLabel,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun KdsConnectionPill(
  connectionState: RealtimeConnectionState,
) {
  val semantic = LocalPizzoraSemanticColors.current
  val (label, color, icon) = when (connectionState) {
    RealtimeConnectionState.CONNECTED -> Triple("Live", semantic.kdsReadyEmerald, Icons.Filled.NotificationsActive)
    RealtimeConnectionState.CONNECTING -> Triple("Connecting…", semantic.kdsPendingAmber, Icons.Filled.Sync)
    RealtimeConnectionState.RECONNECTING -> Triple("Reconnecting…", semantic.kdsPendingAmber, Icons.Filled.Sync)
    RealtimeConnectionState.DISCONNECTED -> Triple("Offline", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.WifiOff)
  }

  Surface(
    color = color.copy(alpha = 0.16f),
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.testTag("kds_connection_badge"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(color),
      )
      Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
      )
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
