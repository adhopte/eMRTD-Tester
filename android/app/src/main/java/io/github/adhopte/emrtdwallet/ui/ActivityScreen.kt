package io.github.adhopte.emrtdwallet.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.adhopte.emrtdwallet.data.ActivityEntry
import io.github.adhopte.emrtdwallet.data.ActivityType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ActivityFilter(val label: String, val types: Set<ActivityType>?) {
    ALL("All", null),
    SHARING("Sharing", setOf(ActivityType.PRESENTED, ActivityType.PRESENTATION_DECLINED, ActivityType.PRESENTATION_FAILED)),
    CREDENTIALS("Credentials", setOf(ActivityType.ISSUED, ActivityType.ISSUANCE_REJECTED, ActivityType.ISSUANCE_FAILED, ActivityType.DELETED)),
    SECURITY("Security", setOf(ActivityType.WALLET_CREATED, ActivityType.SECURITY_CHANGED)),
}

private fun ActivityType.visual(): Pair<ImageVector, Color> = when (this) {
    ActivityType.WALLET_CREATED -> Icons.Filled.Shield to InGroupe.Blue
    ActivityType.SECURITY_CHANGED -> Icons.Filled.Lock to InGroupe.Blue
    ActivityType.ISSUED -> Icons.Filled.AddCard to Color(0xFF2E7D32)
    ActivityType.ISSUANCE_REJECTED, ActivityType.ISSUANCE_FAILED -> Icons.Filled.ErrorOutline to InGroupe.Red
    ActivityType.PRESENTED -> Icons.Filled.Send to InGroupe.SkyBlue
    ActivityType.PRESENTATION_DECLINED -> Icons.Filled.Block to Color(0xFFF9A825)
    ActivityType.PRESENTATION_FAILED -> Icons.Filled.ErrorOutline to InGroupe.Red
    ActivityType.DELETED -> Icons.Filled.Delete to Color.Gray
}

/** Wallet activity history: issuance, presentations (what was shared with whom), security events. */
@Composable
fun ActivityScreen(vm: MainViewModel, onBack: () -> Unit) {
    val entries by vm.activity.entries.collectAsState()
    ActivityHistory(entries, onClear = { vm.activity.clear() }, onBack = onBack)
}

@Composable
fun ActivityHistory(entries: List<ActivityEntry>, onClear: () -> Unit, onBack: () -> Unit) {
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = entries.filter { filter.types == null || it.type in filter.types!! }
    val zone = ZoneId.systemDefault()
    val groups = shown.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    Scaffold(topBar = {
        SimpleTopBar("Activity history", onBack) {
            if (entries.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
        }
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ActivityFilter.entries) { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label) }) }
                }
            }
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, Modifier.size(64.dp), tint = InGroupe.Grey)
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Text("Credentials you add and data you share appear here. The history stays on this phone.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            groups.forEach { (day, list) ->
                item(key = day.toString()) {
                    Text(dayLabel(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp))
                }
                items(list, key = { it.id }) { ActivityRow(it) }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear activity history?") },
        text = { Text("This removes the local history only; credentials are not affected.") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
    }
}

@Composable
private fun ActivityRow(e: ActivityEntry) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, color) = e.type.visual()
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.time))
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row {
                    Text(e.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(time, style = MaterialTheme.typography.labelSmall)
                }
                e.party?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (expanded) {
                    if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (e.items.isNotEmpty()) {
                        Text(if (e.type == ActivityType.PRESENTED) "Shared:" else "Items:", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp))
                        e.items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                } else if (e.items.isNotEmpty()) {
                    Text(e.items.take(4).joinToString(", ") + if (e.items.size > 4) "…" else "",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}
