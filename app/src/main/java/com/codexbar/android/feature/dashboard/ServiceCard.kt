package com.codexbar.android.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.ui.service.iconRes
import com.codexbar.android.ui.service.logoColor
import java.time.Duration
import java.time.Instant

@Composable
fun ServiceCard(
    cardData: ServiceCardData,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cardData.service.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cardData.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                AccountStatePill(
                    label = cardData.statusLabel(),
                    isError = cardData.error != null
                )
            }

            if (cardData.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatError(cardData.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))

                val primaryWindow = cardData.windows.firstOrNull()
                primaryWindow?.let { window ->
                    QuotaGaugeBar(
                        utilization = window.utilization,
                        label = window.label,
                        showPercentage = true,
                        resetsAt = window.resetsAt
                    )
                }

                val secondaryWindows = cardData.windows.drop(1)

                if (secondaryWindows.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        secondaryWindows.forEach { window ->
                            QuotaGaugeBar(
                                utilization = window.utilization,
                                label = window.label,
                                showPercentage = true,
                                resetsAt = window.resetsAt
                            )
                        }
                    }
                }

                cardData.extraUsage?.let { extra ->
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = "Credits: ${extra.currency} ${String.format("%.2f", extra.usedCredits)} / ${String.format("%.2f", extra.monthlyLimit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cardData.fetchedAt?.let { "Updated ${formatElapsed(it)}" } ?: "Not updated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountStatePill(
    label: String,
    isError: Boolean
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            }
        )
    }
}

@Composable
fun ServiceLogo(
    service: AiService,
    modifier: Modifier = Modifier.size(40.dp)
) {
    val logoColor = service.logoColor()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = logoColor.copy(alpha = 0.13f),
        contentColor = logoColor
    ) {
        Icon(
            painter = painterResource(id = service.iconRes()),
            contentDescription = service.displayName,
            modifier = Modifier.padding(8.dp),
            tint = logoColor
        )
    }
}

private fun ServiceCardData.statusLabel(): String {
    if (error != null) return "Fail"
    val rawTier = tier?.trim().orEmpty()
    if (rawTier.isBlank()) return "OK"
    return rawTier.lowercase().replaceFirstChar { it.uppercase() }
}

private fun formatError(error: AppError): String {
    return when (error) {
        is AppError.NetworkError -> "Network error"
        is AppError.AuthError -> if (error.service == AiService.CLAUDE && error.message.isNotBlank()) error.message else if (error.isTerminal) "Re-authentication required" else "Auth error"
        is AppError.RateLimited -> "Rate limited"
        is AppError.ParseError -> error.message ?: "Response parse error"
        is AppError.CredentialNotFound -> "No credentials configured"
        is AppError.ServiceUnavailable -> "Service unavailable"
    }
}

private fun formatElapsed(fetchedAt: Instant): String {
    val elapsed = Duration.between(fetchedAt, Instant.now())
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()} min ago"
        else -> "${elapsed.toHours()}h ago"
    }
}
