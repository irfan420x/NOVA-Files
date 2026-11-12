package com.nova.files.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nova.files.data.CategoryCounts
import com.nova.files.data.FileRepo
import com.nova.files.data.StorageVolumeInfo
import com.nova.files.data.extensionLower
import com.nova.files.data.formatSize
import com.nova.files.data.formatSizeDecimal
import com.nova.files.data.isImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Category(val id: String, val title: String, val icon: ImageVector, val count: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDir: (File) -> Unit, onOpenCategory: (String) -> Unit,
    onOpenFile: (File, List<File>) -> Unit,
    darkTheme: Boolean, onToggleTheme: () -> Unit,
    onOpenErrorLog: () -> Unit = {},
    onOpenApps: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(FileRepo.storageStats()) }
    var counts by remember { mutableStateOf<CategoryCounts?>(null) }
    var recents by remember { mutableStateOf<List<File>>(emptyList()) }
    var volumes by remember { mutableStateOf<List<StorageVolumeInfo>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // v1.8: how many errors are recorded (0 = nothing went wrong).
    var errorCount by remember { mutableStateOf(0) }

    fun loadVolumes() {
        scope.launch { volumes = FileRepo.listStorageVolumes(context) }
    }

    LaunchedEffect(Unit) {
        refreshing = true
        stats = FileRepo.storageStats()
        counts = FileRepo.categoryCounts(context)
        recents = FileRepo.recentFiles(context)
        errorCount = com.nova.files.data.ErrorLog.count(context)
        loadVolumes()
        refreshing = false
    }

    // Refresh the volume list the moment a USB drive / memory card is
    // plugged in or pulled out, instead of waiting for a tab switch.
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { loadVolumes() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    val root = FileRepo.internalRoot()
    val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val usedPct = if (stats.partitionTotalBytes > 0) (stats.usedBytes * 100 / stats.partitionTotalBytes).toInt() else 0
    val categories = listOf(
        Category("images", "Images", Icons.Filled.Image, counts?.images ?: 0),
        Category("videos", "Videos", Icons.Filled.Videocam, counts?.videos ?: 0),
        Category("audio", "Audio", Icons.Filled.Audiotrack, counts?.audio ?: 0),
        Category("documents", "Documents", Icons.Filled.Description, counts?.documents ?: 0),
        Category("apks", "APKs", Icons.Filled.Android, counts?.apks ?: 0),
        Category("downloads", "Downloads", Icons.Filled.Download, counts?.downloads ?: 0)
    )
    val quick = listOf(
        "Download" to File(root, "Download"),
        "Pictures" to File(root, "Pictures"),
        "DCIM" to File(root, "DCIM"),
        "Music" to File(root, "Music"),
        "Documents" to File(root, "Documents"),
        "Movies" to File(root, "Movies")
    ).filter { it.second.exists() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("NOVA Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Your storage, under control", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // v1.7: light / dark theme switch (persisted).
                IconButton(onClick = onToggleTheme) {
                    Icon(if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (darkTheme) "Light theme" else "Dark theme")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Internal storage", fontWeight = FontWeight.Bold)
                        Text("$usedPct% used", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { usedPct / 100f },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${formatSizeDecimal(stats.usedBytes)} used  •  ${formatSizeDecimal(stats.freeBytes)} free")
                    Text(
                        "of ${formatSizeDecimal(stats.partitionTotalBytes)} user storage  •  ${formatSizeDecimal(stats.totalBytes)} total",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (stats.systemBytes > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "System ${formatSizeDecimal(stats.systemBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (refreshing) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // Removable volumes (USB OTG / memory cards) — tap to browse them.
        items(volumes) { vol ->
            val volPct = if (vol.totalBytes > 0) (vol.usedBytes * 100 / vol.totalBytes).toInt() else 0
            Card(
                Modifier.fillMaxWidth().clickable { onOpenDir(vol.dir) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Usb, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(vol.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("$volPct% used", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { volPct / 100f },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${formatSizeDecimal(vol.usedBytes)} used  •  ${formatSizeDecimal(vol.freeBytes)} free")
                    Text(
                        "of ${formatSizeDecimal(vol.totalBytes)} total",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item { Text("Categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(categories.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { cat ->
                    Card(
                        Modifier.weight(1f).clickable { onOpenCategory(cat.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(cat.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(cat.title, fontWeight = FontWeight.Bold)
                            Text("${cat.count} items", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Text("Recent files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (recents.isEmpty()) {
            item { Text("No recent files", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(recents.take(10)) { f ->
            ListItem(
                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${formatSize(f.length())} • ${com.nova.files.data.fileKind(f)} • ${dateFmt.format(Date(f.lastModified()))}") },
                leadingContent = {
                    if (isImage(f)) {
                        AsyncImage(model = f, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                    } else {
                        Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    }
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        if (isImage(f) || f.extensionLower() == "txt") onOpenFile(f, recents)
                        else if (!FileRepo.openWithIntent(context, f)) status = "No app can open this file"
                    }
            )
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item { Text("Quick folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(quick) { (name, dir) ->
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(dir.path) },
                leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenDir(dir) }
            )
        }
        // v1.14: the App Manager — every installed app with its size,
        // APK backup to Downloads/NOVA/Apps, and uninstall.
        item {
            ListItem(
                headlineContent = { Text("App manager") },
                supportingContent = { Text("Installed apps — back up APKs, uninstall, open") },
                leadingContent = { Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenApps() }
            )
        }
        // v1.8: the error log — every crash and failed operation is recorded
        // here with its time, so problems can be reported with the exact error.
        item {
            ListItem(
                headlineContent = { Text("Error log") },
                supportingContent = {
                    Text(if (errorCount == 0) "No errors recorded" else "$errorCount ${if (errorCount == 1) "entry" else "entries"} — tap to view")
                },
                leadingContent = { Icon(Icons.Filled.BugReport, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenErrorLog() }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
