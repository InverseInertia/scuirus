package pantry.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Uncommitted changes, the commit box, sync, and history. Nothing leaves the phone until it is committed. */
@Composable
fun ChangesScreen(vm: PantryViewModel) {
    var message by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var revertSha by remember { mutableStateOf<String?>(null) }
    val dirty = vm.status.changedPaths

    LaunchedEffect(Unit) { vm.loadHistory() }
    // Refill the box with the generated summary whenever the set of pending changes changes.
    LaunchedEffect(vm.suggestedMessage) { message = vm.suggestedMessage }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dirty.isEmpty()) {
                        Text("Everything is committed.", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("${dirty.size} changed file${if (dirty.size == 1) "" else "s"} not committed", style = MaterialTheme.typography.titleMedium)
                        Text(dirty.take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(message, { message = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.commit(message) }, enabled = message.isNotBlank()) { Text("Commit") }
                            OutlinedButton(onClick = { confirmDiscard = true }) { Text("Discard") }
                        }
                    }
                    val ahead = vm.status.ahead
                    Text(
                        when {
                            ahead == null -> "No remote branch seen yet."
                            ahead == 0 -> "Up to date with the server as of the last sync."
                            else -> "$ahead commit${if (ahead == 1) "" else "s"} waiting to be pushed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = vm::sync, enabled = dirty.isEmpty() && !vm.busy) { Text(if (vm.busy) "Syncing..." else "Sync with server") }
                    if (dirty.isNotEmpty()) Text("Commit or discard first. Sync only runs on a clean tree.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (vm.dataWarnings.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Data to check", style = MaterialTheme.typography.titleMedium)
                        vm.dataWarnings.take(8).forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                        if (vm.dataWarnings.size > 8) Text("and ${vm.dataWarnings.size - 8} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        items(vm.history, key = { it.sha }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(c.message.lines().first(), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            c.sha.take(7) + " \u00B7 " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(c.time.atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { revertSha = c.sha }, enabled = dirty.isEmpty()) { Text("Revert") }
                }
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard ${dirty.size} changed files?") },
        text = { Text("Everything since the last commit is deleted. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.discard(); confirmDiscard = false }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
    )
    revertSha?.let { sha ->
        AlertDialog(
            onDismissRequest = { revertSha = null },
            title = { Text("Revert ${sha.take(7)}?") },
            text = { Text("This adds a new commit that undoes that one. It fails if later commits changed the same lines.") },
            confirmButton = { TextButton(onClick = { vm.revert(sha); revertSha = null }) { Text("Revert") } },
            dismissButton = { TextButton(onClick = { revertSha = null }) { Text("Cancel") } },
        )
    }
}
