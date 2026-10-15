package pantry.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pantry.app.data.Settings

private enum class Tab(val label: String) { STOCK("Stock"), SCAN("Scan"), RECEIPT("Receipt"), CHANGES("Changes") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryRoot(vm: PantryViewModel, settings: Settings) {
    var tab by remember { mutableStateOf(Tab.STOCK) }
    var showSettings by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(vm.snackbar) {
        vm.snackbar?.let { snack.showSnackbar(it); vm.snackbar = null }
    }

    ScuirusTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scuirus") },
                    actions = { IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Filled.Settings, "Settings") } },
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && !showSettings,
                            onClick = { tab = t; showSettings = false },
                            label = { Text(t.label) },
                            icon = {
                                val icon = when (t) {
                                    Tab.STOCK -> Icons.Filled.Inventory2
                                    Tab.SCAN -> Icons.Filled.CameraAlt
                                    Tab.RECEIPT -> Icons.Filled.Receipt
                                    Tab.CHANGES -> Icons.Filled.Sync
                                }
                                if (t == Tab.CHANGES && vm.status.changedPaths.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text(vm.status.changedPaths.size.toString()) } }) { Icon(icon, t.label) }
                                } else Icon(icon, t.label)
                            },
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
                when {
                    vm.startupError != null && !showSettings -> {
                        Text(vm.startupError!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { showSettings = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Open settings") }
                    }
                    showSettings -> SettingsScreen(vm, settings)
                    !vm.ready -> Text("Opening repository...", modifier = Modifier.padding(16.dp))
                    else -> when (tab) {
                        Tab.STOCK -> InventoryScreen(vm)
                        Tab.SCAN -> ScanScreen(vm)
                        Tab.RECEIPT -> ReceiptScreen(vm)
                        Tab.CHANGES -> ChangesScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: PantryViewModel, settings: Settings) {
    var url by remember { mutableStateOf(settings.remoteUrl) }
    var user by remember { mutableStateOf(settings.username) }
    var token by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp)) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text(
            "An HTTPS git URL, for example https://git.example.com/me/pantry-data.git. " +
                "Use an access token with write access to that one repository, not your account password.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Repository URL") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            token, { token = it }, label = { Text(if (settings.token.isBlank()) "Access token" else "Access token (saved, leave blank to keep)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = { vm.saveSettings(url, user, token) }, modifier = Modifier.padding(top = 12.dp)) { Text("Save") }
        Text("This device writes events as: ${settings.deviceId}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
    }
}
