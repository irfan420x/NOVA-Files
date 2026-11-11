@file:OptIn(ExperimentalFoundationApi::class)
package com.nova.files.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.nova.files.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    dir: File, category: String?, onNavigate: (File) -> Unit,
    onOpenFile: (File, List<File>) -> Unit,
    clipboard: Pair<List<File>, Boolean>?,
    onClipboardChange: (Pair<List<File>, Boolean>?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // v1.7: sort / view / hidden survive restarts (v1.0–v1.6 forgot them).
    val initialPrefs = remember { FileRepo.loadUiPrefs(context) }
    var sort by remember { mutableStateOf(initialPrefs.sort) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(initialPrefs.grid) }
    var showHidden by remember { mutableStateOf(initialPrefs.showHidden) }
    var folderCounts by remember { mutableStateOf<Map<File, Int>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<File>>(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var contextFile by remember { mutableStateOf<File?>(null) }
    var detailsFile by remember { mutableStateOf<File?>(null) }
    var extractTarget by remember { mutableStateOf<File?>(null) }
    var deleteTargets by remember { mutableStateOf<List<File>?>(null) }

    fun load() {
        scope.launch {
            loading = true
            val q = query.trim()
            files = when {
                category != null -> {
                    val all = FileRepo.listCategoryFiles(context, category)
                    if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
                }
                q.isNotEmpty() -> {
                    status = "Searching…"
                    val found = FileRepo.searchFiles(dir, q, showHidden)
                    status = if (found.size >= 300) "Showing first 300 matches" else "${found.size} matches"
                    found
                }
                else -> FileRepo.listFiles(dir, sort, q, showHidden)
            }
            // v1.7: folder item counts (one cheap readdir per folder).
            folderCounts = if (category == null && q.isEmpty()) {
                withContext(Dispatchers.IO) { files.filter { it.isDirectory }.associateWith { it.listFiles()?.size ?: 0 } }
            } else emptyMap()
            loading = false
        }
    }
    LaunchedEffect(dir, sort, showHidden, category) { load() }
    LaunchedEffect(sort, grid, showHidden) {
        FileRepo.updateUiPrefs(context) { it.copy(sort = sort, grid = grid, showHidden = showHidden) }
    }
    LaunchedEffect(query) {
        if (query.isEmpty()) { status = null; load() }
    }

    // System back: clear selection / close search / go to parent folder first,
    // only then leave the screen (v1.2 had no BackHandler — back exited the app).
    val canGoUp = category == null && dir != FileRepo.internalRoot() && dir.parentFile != null
    BackHandler(enabled = selected.isNotEmpty() || searching || canGoUp) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> { searching = false; query = ""; load() }
            canGoUp -> onNavigate(dir.parentFile!!)
        }
    }

    val title = category?.replaceFirstChar { it.uppercase() } ?: dir.name.ifEmpty { "Internal storage" }
    val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (category == null && dir.parentFile != null && dir != FileRepo.internalRoot()) {
                        IconButton(onClick = { onNavigate(dir.parentFile!!) }) { Icon(Icons.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { val n = selected.size; onClipboardChange(selected.toList() to false); selected = emptySet(); status = "Copied $n items" }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                        IconButton(onClick = { onClipboardChange(selected.toList() to true); selected = emptySet() }) { Icon(Icons.Filled.DriveFileMove, "Move") }
                        IconButton(onClick = { deleteTargets = selected.toList() }) { Icon(Icons.Filled.Delete, "Delete") }
                        IconButton(onClick = { FileRepo.shareFiles(context, selected.toList()); selected = emptySet() }) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = {
                            val items = selected.toList()
                            scope.launch {
                                progress = "Compressing…"
                                val dest = File(items.first().parentFile ?: dir, "${items.first().nameWithoutExtension}.zip")
                                val ok = FileRepo.zipFiles(items, dest)
                                progress = null; selected = emptySet()
                                status = if (ok) "Saved ${dest.name}" else "Compress failed"
                                if (!ok) ErrorLog.log(context, "Compress", "Failed to compress ${items.size} item(s)")
                                load()
                            }
                        }) { Icon(Icons.Filled.Archive, "Compress") }
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                    } else {
                        IconButton(onClick = { searching = !searching; if (!searching) { query = ""; load() } }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Filled.List else Icons.Filled.GridView, "Switch view") }
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.values().forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { sort = mode; sortMenu = false; load() })
                        }
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("New folder") }, onClick = { moreMenu = false; showNewFolder = true })
                        DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                            onClick = { moreMenu = false; showHidden = !showHidden })
                        DropdownMenuItem(text = { Text("Select all") }, onClick = { moreMenu = false; selected = files.toSet() })
                    }
                }
            )
        },
        floatingActionButton = {
            if (clipboard != null && category == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val (items, move) = clipboard!!
                        scope.launch {
                            progress = "Working…"
                            val ok = FileRepo.copyFiles(items, dir, move) { done, total -> progress = "$done / $total" }
                            progress = null; onClipboardChange(null)
                            status = if (!ok) "Couldn't write here — storage may be read-only"
                            else if (move) "Moved" else "Pasted"
                            if (!ok) ErrorLog.log(context, "Paste", "Failed to ${if (move) "move" else "copy"} ${items.size} item(s) to ${dir.path}")
                            load()
                        }
                    },
                    icon = { Icon(Icons.Filled.ContentPaste, null) },
                    text = { Text(if (clipboard!!.second) "Move here" else "Paste here") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search files…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    trailingIcon = {
                        IconButton(onClick = { load() }) { Icon(Icons.Filled.Search, "Search") }
                    }
                )
            }
            if (category == null) {
                Text(
                    dir.path, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary) }
            progress?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No files here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (grid) {
                LazyVerticalGrid(columns = GridCells.Fixed(3), Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { f -> FileGridItem(f, f in selected, folderCounts[f], onClick = { onFileTap(f, files, selected, { selected = it }, onNavigate, onOpenFile, context, onError = { status = it }) }, onLongClick = { contextFile = f }) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files) { f ->
                        FileRow(f, f in selected, dateFmt, folderCounts[f],
                            onClick = { onFileTap(f, files, selected, { selected = it }, onNavigate, onOpenFile, context, onError = { status = it }) },
                            onLongClick = { contextFile = f },
                            onMenu = { contextFile = f })
                    }
                }
            }
        }
    }

    // Long-press context menu: Open / Share / Copy / Move / Rename / Compress /
    // Extract (zip only) / Delete / Details / Select
    contextFile?.let { f ->
        ModalBottomSheet(onDismissRequest = { contextFile = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
                Text(f.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // v1.8: the menu says what the file is and what "Open" will do —
                // View for images, Play for video/audio, Install for APKs.
                Text(fileKind(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                val isArchive = isArchive(f)
                val openLabel = when {
                    f.isDirectory -> "Open"
                    isImage(f) -> "View"
                    isVideo(f) || isAudio(f) -> "Play"
                    isApk(f) -> "Install"
                    else -> "Open"
                }
                MenuRow(Icons.Filled.OpenInNew, openLabel) {
                    contextFile = null
                    onFileTap(f, files, selected, { selected = it }, onNavigate, onOpenFile, context, onError = { status = it })
                }
                MenuRow(Icons.Filled.Share, "Share") { contextFile = null; FileRepo.shareFiles(context, listOf(f)) }
                MenuRow(Icons.Filled.ContentCopy, "Copy") {
                    contextFile = null; onClipboardChange(listOf(f) to false)
                    status = "Copied — open a folder to paste"
                }
                MenuRow(Icons.Filled.DriveFileMove, "Move") {
                    contextFile = null; onClipboardChange(listOf(f) to true)
                    status = "Moving — open a folder to move here"
                }
                MenuRow(Icons.Filled.Edit, "Rename") { contextFile = null; renameTarget = f }
                if (isArchive) {
                    MenuRow(Icons.Filled.Unarchive, "Extract here") {
                        contextFile = null
                        scope.launch {
                            progress = "Extracting…"
                            val ok = FileRepo.extractArchive(f, File(f.parentFile, f.name.substringBefore('.')))
                            progress = null
                            status = if (ok) "Extracted to ${f.name.substringBefore('.')}" else "Extract failed"
                            if (!ok) ErrorLog.log(context, "Extract", "Failed to extract ${f.name}")
                            load()
                        }
                    }
                    MenuRow(Icons.Filled.FolderOpen, "Extract to…") {
                        contextFile = null; extractTarget = f
                    }
                } else {
                    MenuRow(Icons.Filled.Archive, "Compress to ZIP") {
                        contextFile = null
                        scope.launch {
                            progress = "Compressing…"
                            val dest = File(f.parentFile, "${f.nameWithoutExtension}.zip")
                            val ok = FileRepo.zipFiles(listOf(f), dest)
                            progress = null
                            status = if (ok) "Saved ${dest.name}" else "Compress failed"
                            if (!ok) ErrorLog.log(context, "Compress", "Failed to compress ${f.name}")
                            load()
                        }
                    }
                }
                MenuRow(Icons.Filled.Delete, "Delete") {
                    contextFile = null; deleteTargets = listOf(f)
                }
                MenuRow(Icons.Filled.Info, "Details") { contextFile = null; detailsFile = f }
                MenuRow(Icons.Filled.CheckBox, "Select") { contextFile = null; selected = selected + f }
            }
        }
    }

    detailsFile?.let { f ->
        var folderInfo by remember(f) { mutableStateOf<Pair<Long, Int>?>(null) }
        LaunchedEffect(f) {
            if (f.isDirectory) {
                folderInfo = withContext(Dispatchers.IO) {
                    var s = 0L; var n = 0
                    f.walkTopDown().forEach { if (it.isFile) { s += it.length(); n++ } }
                    s to n
                }
            }
        }
        AlertDialog(
            onDismissRequest = { detailsFile = null },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        if (f.isDirectory) {
                            folderInfo?.let { (size, count) -> "Folder • ${formatSize(size)} • $count files" } ?: "Folder • calculating size…"
                        } else "${formatSize(f.length())} • ${fileKind(f)} • ${dateFmt.format(Date(f.lastModified()))}"
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(f.path, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Modified ${dateFmt.format(Date(f.lastModified()))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { detailsFile = null }) { Text("Close") } }
        )
    }

    // Delete confirmation (v1.2 deleted immediately with no confirm)
    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text(if (targets.size == 1) "Move to Trash?" else "Move ${targets.size} items to Trash?") },
            text = { Text(if (targets.size == 1) targets.first().name else targets.take(3).joinToString("\n") { it.name } + if (targets.size > 3) "\n…and ${targets.size - 3} more" else "") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { FileRepo.moveToTrash(context, targets); selected = emptySet(); status = "Moved to Trash"; load() }
                    deleteTargets = null
                }) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("Cancel") } }
        )
    }

    // Extract destination picker (v1.2 only had "Extract here" in the same folder)
    extractTarget?.let { archive ->
        FolderPickerDialog(
            startDir = archive.parentFile ?: FileRepo.internalRoot(),
            title = "Extract to…",
            confirmLabel = "Extract here",
            onDismiss = { extractTarget = null },
            onPick = { dest ->
                extractTarget = null
                scope.launch {
                    progress = "Extracting…"
                    val ok = FileRepo.extractArchive(archive, File(dest, archive.name.substringBefore('.')))
                    progress = null
                    status = if (ok) "Extracted to ${archive.name.substringBefore('.')}" else "Extract failed"
                    if (!ok) ErrorLog.log(context, "Extract", "Failed to extract ${archive.name} to ${dest.path}")
                    load()
                }
            }
        )
    }

    renameTarget?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        var renameError by remember(target) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it; renameError = null }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    renameError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    when {
                        trimmed.isEmpty() -> renameError = "Name cannot be empty"
                        trimmed != target.name && target.parentFile?.let { File(it, trimmed).exists() } == true ->
                            renameError = "A file with this name already exists"
                        else -> scope.launch {
                            withContext(Dispatchers.IO) { target.renameTo(File(target.parentFile, trimmed)) }
                            renameTarget = null; load()
                        }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    if (showNewFolder) {
        var name by remember { mutableStateOf("New folder") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { File(dir, name).mkdirs() }; showNewFolder = false; load() }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } }
        )
    }
}

private fun onFileTap(
    f: File, siblings: List<File>, selected: Set<File>, setSelected: (Set<File>) -> Unit,
    onNavigate: (File) -> Unit, onOpenFile: (File, List<File>) -> Unit, context: android.content.Context,
    onError: (String) -> Unit = {}
) {
    if (selected.isNotEmpty()) {
        setSelected(if (f in selected) selected - f else selected + f)
        return
    }
    when {
        f.isDirectory -> onNavigate(f)
        isImage(f) || f.extensionLower() == "txt" -> onOpenFile(f, siblings)
        else -> if (!FileRepo.openWithIntent(context, f)) {
            ErrorLog.log(context, "Open", "No app can open ${f.name} (${fileKind(f)})")
            onError("No app can open this file")
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FileRow(f: File, isSelected: Boolean, dateFmt: SimpleDateFormat, childCount: Int? = null, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    ListItem(
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (f.isDirectory) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = {
            Text(if (f.isDirectory) {
                if (childCount != null) "Folder • $childCount items • ${dateFmt.format(Date(f.lastModified()))}"
                else "Folder • ${dateFmt.format(Date(f.lastModified()))}"
            } else "${formatSize(f.length())} • ${fileKind(f)} • ${dateFmt.format(Date(f.lastModified()))}")
        },
        leadingContent = {
            if (isImage(f)) {
                AsyncImage(model = f, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        },
        trailingContent = {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "More options") }
        },
        modifier = Modifier
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

@Composable
fun FileGridItem(f: File, isSelected: Boolean, childCount: Int? = null, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (isImage(f)) {
                AsyncImage(model = f, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text(fileKind(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.isDirectory && childCount != null) {
                Text("$childCount items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun fileIcon(f: File) = when {
    f.isDirectory -> Icons.Filled.Folder
    isImage(f) -> Icons.Filled.Image
    isVideo(f) -> Icons.Filled.Videocam
    isAudio(f) -> Icons.Filled.Audiotrack
    isDocument(f) -> Icons.Filled.Description
    isApk(f) -> Icons.Filled.Android
    isArchive(f) -> Icons.Filled.Archive
    else -> Icons.Filled.InsertDriveFile
}
