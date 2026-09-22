package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.loop.AgentState
import com.example.ui.theme.AgentEmerald
import com.example.ui.theme.AgentWhatsAppGreen

@Composable
fun AgentStatusBadge(
    state: AgentState,
    modifier: Modifier = Modifier
) {
    val (statusText, dotColor, bgColor) = when (state) {
        AgentState.IDLE -> Triple("Ready (Idle)", AgentEmerald, AgentEmerald.copy(alpha = 0.12f))
        AgentState.THINKING -> Triple("Thinking...", Color(0xFFF59E0B), Color(0xFFF59E0B).copy(alpha = 0.15f))
        AgentState.CALLING_TOOL -> Triple("Calling Tool", Color(0xFF3B82F6), Color(0xFF3B82F6).copy(alpha = 0.15f))
        AgentState.WAITING_TOOL -> Triple("Waiting Tool", Color(0xFF8B5CF6), Color(0xFF8B5CF6).copy(alpha = 0.15f))
        AgentState.DELEGATING -> Triple("Delegating", Color(0xFF6366F1), Color(0xFF6366F1).copy(alpha = 0.15f))
        AgentState.WAITING_SUB_AGENT -> Triple("Sub-Agent...", Color(0xFF6366F1), Color(0xFF6366F1).copy(alpha = 0.15f))
        AgentState.REFLECTING -> Triple("Reflecting", Color(0xFFEC4899), Color(0xFFEC4899).copy(alpha = 0.15f))
        AgentState.RETRYING -> Triple("Retrying", Color(0xFFF97316), Color(0xFFF97316).copy(alpha = 0.15f))
        AgentState.FALLBACK -> Triple("Echo Fallback", Color(0xFF64748B), Color(0xFF64748B).copy(alpha = 0.15f))
        AgentState.WAITING_APPROVAL -> Triple("Approval Req.", Color(0xFFEAB308), Color(0xFFEAB308).copy(alpha = 0.15f))
        AgentState.COMPLETED -> Triple("Done", AgentEmerald, AgentEmerald.copy(alpha = 0.12f))
        AgentState.FAILED -> Triple("Error", Color(0xFFEF4444), Color(0xFFEF4444).copy(alpha = 0.15f))
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = dotColor
        )
    }
}

@Composable
fun ConnectionStatusBadge(
    isConnected: Boolean,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val dotColor = if (isConnected) AgentWhatsAppGreen else Color(0xFF94A3B8)
    val bgColor = if (isConnected) AgentWhatsAppGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isConnected) "Connected" else statusText.ifBlank { "Disconnected" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (isConnected) AgentWhatsAppGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (actionLabel != null && onActionClick != null) {
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
        ),
        onClick = onClick ?: {}
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onActionClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(actionLabel)
            }
        }
    }
}
