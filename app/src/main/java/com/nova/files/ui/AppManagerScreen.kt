package com.nova.files.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nova.files.data.AppInfo
import com.nova.files.data.AppRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf<String?>(null) }

    fun onStatus(msg: String) { status = msg }

    LaunchedEffect(status) {
        if (status != null) { kotlinx.coroutines.delay(3000); status = null }
    }

    fun load() {
        scope.launch {
            loading = true
            apps = withContext(Dispatchers.IO) { AppRepo.listApps(context) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    // v1.17: refresh when returning from the system uninstaller — the old
    // list kept showing an app that was just uninstalled.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val visible = remember(apps, query, showSystem) {
        apps.filter { app ->
            (showSystem || !app.isSystem) &&
                (query.isBlank() || app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App manager", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${visible.size} app${if (visible.size != 1) "s" else ""}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("System apps", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${app.packageName} • v${app.versionName.ifBlank { app.versionCode.toString() }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "APK ${com.nova.files.data.formatSize(app.apkBytes)}" +
                                                if (app.isSystem) " • System app" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = AppRepo.launchIntent(context, app)
                                            if (intent != null) {
                                                try { context.startActivity(intent) }
                                                catch (e: Exception) { onStatus("Couldn't open ${app.label}") }
                                            } else onStatus("This app can't be opened")
                                        }
                                    ) {
                                        Icon(Icons.Filled.OpenInNew, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Open")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            backingUp = app.packageName
                                            scope.launch {
                                                val file = withContext(Dispatchers.IO) {
                                                    AppRepo.backupApk(context, app)
                                                }
                                                backingUp = null
                                                if (file != null) onStatus("APK saved to Downloads/NOVA/Apps/${file.parentFile?.name}/${file.name}")
                                                else onStatus("Couldn't back up ${app.label}")
                                            }
                                        },
                                        enabled = backingUp == null
                                    ) {
                                        Icon(Icons.Filled.Download, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (backingUp == app.packageName) "Saving…" else "Back up APK")
                                    }
                                    if (!app.isSystem) {
                                        OutlinedButton(
                                            onClick = {
                                                try { context.startActivity(AppRepo.uninstallIntent(app)) }
                                                catch (e: Exception) { onStatus("Couldn't uninstall ${app.label}") }
                                            }
                                        ) {
                                            Icon(Icons.Filled.Delete, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Uninstall")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
