package com.codexbar.android.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.AiService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToCredentials: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var selectedCard by remember { mutableStateOf<ServiceCardData?>(null) }
    var pendingDelete by remember { mutableStateOf<ServiceCardData?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshIfStale()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("CodexBar") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = onNavigateToCredentials) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "Add model")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is DashboardUiState.Success -> {
                    if (state.cards.isEmpty()) {
                        EmptyState(onAddModel = onNavigateToCredentials)
                    } else {
                        CardList(
                            cards = state.cards,
                            errorBanner = null,
                            onCardClick = { selectedCard = it },
                            onDelete = { pendingDelete = it }
                        )
                    }
                }

                is DashboardUiState.PartialSuccess -> {
                    CardList(
                        cards = state.cards,
                        errorBanner = null,
                        onCardClick = { selectedCard = it },
                        onDelete = { pendingDelete = it }
                    )
                }

                is DashboardUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Failed to load quota data",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    selectedCard?.let { card ->
        ServiceDetailDialog(
            card = card,
            onDismiss = { selectedCard = null }
        )
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove account?") },
            text = {
                Text("Remove ${card.displayName} from CodexBar.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount(card.service, card.accountId)
                        pendingDelete = null
                    }
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CardList(
    cards: List<ServiceCardData>,
    errorBanner: String?,
    onCardClick: (ServiceCardData) -> Unit,
    onDelete: (ServiceCardData) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (errorBanner != null) {
            item {
                Text(
                    text = errorBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        items(cards, key = { "${it.service.name}_${it.accountId}" }) { card ->
            ServiceCard(
                cardData = card,
                onClick = { onCardClick(card) },
                onDelete = { onDelete(card) }
            )
        }
    }
}

@Composable
private fun ServiceDetailDialog(
    card: ServiceCardData,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.tier?.let { tier ->
                    Text(
                        text = "Tier: $tier",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                card.error?.let { error ->
                    Text(
                        text = "Status: ${formatDetailError(error)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                card.windows.forEach { window ->
                    Text(
                        text = "${window.label}: ${(window.utilization * 100).toInt()}% - reset ${formatReset(window.resetsAt)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                card.extraUsage?.let { extra ->
                    Text(
                        text = "Credits: ${extra.currency} ${String.format("%.2f", extra.usedCredits)} / ${String.format("%.2f", extra.monthlyLimit)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun formatReset(resetsAt: Instant?): String {
    if (resetsAt == null) return "unknown"
    return DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(resetsAt)
}

private fun formatDetailError(error: AppError): String {
    return when (error) {
        is AppError.NetworkError -> "Network error"
        is AppError.AuthError -> if (error.service == AiService.CLAUDE && error.message.isNotBlank()) error.message else if (error.isTerminal) "Re-authentication required" else "Authentication retry pending"
        is AppError.RateLimited -> "Rate limited"
        is AppError.ParseError -> "Response parse error"
        is AppError.CredentialNotFound -> "No credentials configured"
        is AppError.ServiceUnavailable -> "Service unavailable"
    }
}

@Composable
private fun EmptyState(onAddModel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AiService.entries.forEach { service ->
                    ServiceLogo(service = service, modifier = Modifier.size(42.dp))
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "No services configured",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Add a model to save credentials and start tracking quota",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onAddModel,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth(0.62f)
            ) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Model")
            }
        }
    }
}
