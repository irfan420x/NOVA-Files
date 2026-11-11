package com.nova.files.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nova.files.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Storage Analyzer ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    onOpenFile: (File, List<File>) -> Unit = { _, _ -> },
    onOpenDir: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(FileRepo.storageStats()) }
    var folders by remember { mutableStateOf<List<Pair<File, Long?>>>(emptyList()) }
    var large by remember { mutableStateOf<List<File>>(emptyList()) }
    var dups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var dupScanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanStatus by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var dupDeleteTarget by remember { mutableStateOf<File?>(null) }

    fun load() {
        scope.launch {
            scanning = true
            stats = FileRepo.storageStats()
            val root = FileRepo.internalRoot()
            val dirs = withContext(Dispatchers.IO) { root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList() }
            // v1.7: show folder names immediately and fill sizes in as they
            // are measured — v1.0–v1.6 blocked the whole screen on the full walk.
            folders = dirs.map { it to null }
            for (d in dirs) {
                val size = FileRepo.folderSize(d)
                folders = folders.map { if (it.first == d) d to size else it }
                    .sortedByDescending { it.second ?: 0L }
            }
            large = FileRepo.largeFiles(root)
            scanning = false
        }
    }
    LaunchedEffect(Unit) { load() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Storage analyzer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Used ${formatSizeDecimal(stats.usedBytes)} of ${formatSizeDecimal(stats.totalBytes)} • Free ${formatSizeDecimal(stats.freeBytes)}")
            if (scanning) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
        }
        item { Text("Biggest folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(folders.take(12)) { (dir, size) ->
            ListItem(
                headlineContent = { Text(dir.name) },
                supportingContent = { Text(size?.let { formatSize(it) } ?: "calculating…") },
                leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onOpenDir(dir) }
            )
        }
        item { Text("Large files (over 50 MB)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(large) { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = { Text("${formatSize(f.length())} • ${f.parentFile?.name}") },
                leadingContent = { Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable {
                    if (isImage(f) || f.extensionLower() == "txt") onOpenFile(f, large)
                    else if (!FileRepo.openWithIntent(context, f)) status = "No app can open this file"
                }
            )
        }
        item {
            Button(
                onClick = {
                    if (dupScanning) { scanJob?.cancel(); return@Button }
                    scanJob = scope.launch {
                        dupScanning = true; scanStatus = "Scanning for duplicates…"
                        try {
                            dups = FileRepo.findDuplicates(FileRepo.internalRoot()) { n -> scanStatus = "Scanned $n files…" }
                            scanStatus = if (dups.isEmpty()) "No duplicates found" else "${dups.size} duplicate groups found"
                        } catch (e: CancellationException) {
                            scanStatus = "Scan stopped"
                        }
                        dupScanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (dupScanning) "Stop scan" else "Find duplicate files") }
            if (scanStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(scanStatus) }
        }
        items(dups) { group ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${group.files.size} copies • ${formatSize(group.size)} each", fontWeight = FontWeight.Bold)
                    group.files.forEach { f ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dupDeleteTarget = f }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // v1.7: duplicate delete asks first and goes to Trash (v1.0–v1.6 deleted
    // permanently, immediately, with no confirm).
    dupDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { dupDeleteTarget = null },
            title = { Text("Move duplicate to Trash?") },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        FileRepo.moveToTrash(context, listOf(target))
                        dups = dups.map { g -> g.copy(files = g.files - target) }.filter { it.files.size > 1 }
                        dupDeleteTarget = null
                    }
                }) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { dupDeleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ---------- Trash ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<List<File>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    fun load() { scope.launch { FileRepo.purgeOldTrash(context); trash = FileRepo.listTrash(context) } }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Trash") }, actions = {
            TextButton(onClick = { scope.launch { FileRepo.emptyTrash(context); status = "Trash emptied"; load() } }) { Text("Empty all") }
        }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Deleted files stay here for 30 days, then are removed. Restore any time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(12.dp))
            if (trash.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Trash is empty") }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trash) { f ->
                        ListItem(
                            headlineContent = { Text(f.name.substringAfter('_', f.name)) },
                            supportingContent = { Text(if (f.isDirectory) "Folder" else formatSize(f.length())) },
                            leadingContent = { Icon(Icons.Filled.Delete, null) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { scope.launch { FileRepo.restoreFromTrash(context, f); status = "Restored"; load() } }) { Text("Restore") }
                                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { FileRepo.deleteFromTrash(context, f) }; load() } }) { Text("Delete") }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Vault ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onOpenFile: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var setupMode by remember { mutableStateOf(FileRepo.vaultPinHash(context) == null) }
    var error by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var moveOutTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    // v1.9: confirm field for PIN setup + the Change PIN dialog state.
    var confirmPin by remember { mutableStateOf("") }
    var showChangePin by remember { mutableStateOf(false) }
    // v1.10: the Remove PIN dialog state.
    var showRemovePin by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
                    val dest = File(FileRepo.vaultDir(context), name)
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        dest.outputStream().use { out -> ins.copyTo(out) }
                    }
                } catch (e: Exception) { }
            }
            files = withContext(Dispatchers.IO) { FileRepo.vaultDir(context).listFiles()?.toList() ?: emptyList() }
        }
    }

    fun load() { scope.launch { files = withContext(Dispatchers.IO) { FileRepo.vaultDir(context).listFiles()?.toList() ?: emptyList() } } }

    // v1.9: one shared fingerprint flow for unlock AND for "Forgot PIN?"
    // reset. The prompt now names BIOMETRIC_STRONG explicitly (the
    // recommended form with a negative button), and cancelling it or
    // tapping "Use PIN" no longer shows a scary error message.
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
    val biometricAvailable = remember {
        try {
            androidx.biometric.BiometricManager.from(context)
                .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        } catch (t: Throwable) {
            // v1.11: catch Throwable, not just Exception — an Error (e.g. a
            // missing class) must also degrade to PIN instead of crashing.
            ErrorLog.log(context, "Vault", "Fingerprint check failed", t)
            false
        }
    }
    fun launchFingerprint(onSuccess: () -> Unit) {
        val act = activity
        if (act == null) { error = "Fingerprint unavailable — use your PIN"; return }
        try {
            val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
            val prompt = androidx.biometric.BiometricPrompt(act, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        // v1.11: the success callback runs outside the try
                        // above, so guard it too — a failure here used to
                        // crash the app instead of falling back to PIN.
                        try {
                            onSuccess()
                        } catch (t: Throwable) {
                            ErrorLog.log(context, "Vault", "Fingerprint success handler failed", t)
                            error = "Fingerprint worked, but opening the vault failed (${t.javaClass.simpleName}) — use your PIN"
                        }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        try {
                            if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                error = errString.toString()
                            }
                        } catch (t: Throwable) {
                            ErrorLog.log(context, "Vault", "Fingerprint error handler failed", t)
                        }
                    }
                })
            prompt.authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Vault")
                    .setSubtitle("Use your fingerprint to unlock")
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Use PIN")
                    .build()
            )
        } catch (t: Throwable) {
            ErrorLog.log(context, "Vault", "Fingerprint unlock failed", t)
            error = "Fingerprint unavailable (${t.javaClass.simpleName}) — use your PIN"
        }
    }

    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(if (setupMode) "Set a vault PIN" else "Enter vault PIN", style = MaterialTheme.typography.titleLarge)
            if (setupMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Choose your own 4–6 digit PIN. There is no default PIN — nobody can open the vault without the PIN you set here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("PIN (4–6 digits)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            if (setupMode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            if (error.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (pin.length < 4) { error = "PIN must be at least 4 digits"; return@Button }
                    if (setupMode) {
                        if (pin != confirmPin) { error = "PINs do not match"; return@Button }
                        FileRepo.setVaultPin(context, pin)
                        unlocked = true; setupMode = false; pin = ""; confirmPin = ""; load()
                    } else if (FileRepo.checkVaultPin(context, pin)) {
                        unlocked = true; pin = ""; load()
                    } else error = "Wrong PIN"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (setupMode) "Create vault" else "Unlock") }
            if (!setupMode && biometricAvailable && activity != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchFingerprint { unlocked = true; pin = ""; load() } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Unlock with fingerprint") }
            }
            if (!setupMode) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    // v1.9: "Forgot PIN?" — prove it is you with a fingerprint,
                    // then set a new PIN. This is the way out when a PIN was
                    // set during an earlier test and forgotten.
                    if (biometricAvailable && activity != null) {
                        launchFingerprint { setupMode = true; pin = ""; confirmPin = ""; error = "" }
                    } else {
                        error = "Fingerprint is not set up, so the PIN cannot be reset from here. Clear the app data in Settings to reset the vault — this also deletes the vault files."
                    }
                }) { Text("Forgot PIN?") }
            }
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Vault") }, actions = {
            TextButton(onClick = { showChangePin = true }) { Text("Change PIN") }
            TextButton(onClick = { showRemovePin = true }) { Text("Remove PIN") }
            TextButton(onClick = { importLauncher.launch("*/*") }) { Text("Import") }
        }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Files in the vault are stored in private app storage — other apps cannot see them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(12.dp))
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Vault is empty. Tap Import to add files.") }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            supportingContent = { Text(formatSize(f.length())) },
                            leadingContent = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { moveOutTarget = f }) { Text("Move out") }
                                    TextButton(onClick = { deleteTarget = f }) { Text("Delete") }
                                }
                            },
                            modifier = Modifier.clickable { if (isImage(f)) onOpenFile(f) }
                        )
                    }
                }
            }
        }
    }

    // v1.7: move a file OUT of the vault (v1.0–v1.6 had no way back at all).
    moveOutTarget?.let { target ->
        FolderPickerDialog(
            startDir = FileRepo.internalRoot(),
            title = "Move out of vault",
            confirmLabel = "Move here",
            onDismiss = { moveOutTarget = null },
            onPick = { dest ->
                moveOutTarget = null
                scope.launch {
                    val ok = FileRepo.copyFiles(listOf(target), dest, move = true) { _, _ -> }
                    status = if (ok) "Moved to ${dest.name}" else "Couldn't move file"
                    load()
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete from vault?") },
            text = { Text("${target.name}\nThis permanently deletes the file — it does not go to Trash.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { FileRepo.deleteRecursively(target) }
                        status = "Deleted from vault"; deleteTarget = null; load()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    // v1.9: change the vault PIN from inside the unlocked vault.
    if (showChangePin) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmNewPin by remember { mutableStateOf("") }
        var changeError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showChangePin = false },
            title = { Text("Change vault PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = currentPin, onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(6); changeError = null },
                        label = { Text("Current PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); changeError = null },
                        label = { Text("New PIN (4–6 digits)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmNewPin, onValueChange = { confirmNewPin = it.filter { c -> c.isDigit() }.take(6); changeError = null },
                        label = { Text("Confirm new PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    changeError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        !FileRepo.checkVaultPin(context, currentPin) -> changeError = "Current PIN is wrong"
                        newPin.length < 4 -> changeError = "New PIN must be at least 4 digits"
                        newPin != confirmNewPin -> changeError = "New PINs do not match"
                        else -> {
                            FileRepo.setVaultPin(context, newPin)
                            showChangePin = false
                            status = "PIN changed"
                        }
                    }
                }) { Text("Change") }
            },
            dismissButton = { TextButton(onClick = { showChangePin = false }) { Text("Cancel") } }
        )
    }

    // v1.10: remove the vault PIN. The vault locks immediately and the next
    // entry asks to set a brand-new PIN — no auto PIN, ever. Vault files
    // stay where they are; only the lock is removed.
    if (showRemovePin) {
        var currentPin by remember { mutableStateOf("") }
        var removeError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showRemovePin = false },
            title = { Text("Remove vault PIN?") },
            text = {
                Column {
                    Text("The vault will lock, and next time you open it you will be asked to set a new PIN. Files already in the vault stay where they are.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = currentPin, onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(6); removeError = null },
                        label = { Text("Current PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    removeError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!FileRepo.checkVaultPin(context, currentPin)) {
                        removeError = "Current PIN is wrong"
                    } else {
                        FileRepo.clearVaultPin(context)
                        showRemovePin = false
                        unlocked = false
                        pin = ""; confirmPin = ""
                        setupMode = true
                        error = ""
                        status = ""
                    }
                }) { Text("Remove PIN") }
            },
            dismissButton = { TextButton(onClick = { showRemovePin = false }) { Text("Cancel") } }
        )
    }
}

// ---------- File viewer (image / text) ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    file: File, files: List<File> = listOf(file), index: Int = 0,
    onNavigate: (Int) -> Unit = {}, onBack: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    // v1.7: pinch-to-zoom for images (v1.0–v1.6 showed them fixed-size).
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    LaunchedEffect(file) {
        scale = 1f; offset = Offset.Zero
        if (file.extensionLower() == "txt") {
            text = withContext(Dispatchers.IO) {
                try { file.readText().take(200_000) } catch (e: Exception) { "Could not read file." }
            }
        } else text = null
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(file.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                if (files.size > 1) {
                    IconButton(onClick = { onNavigate((index - 1).coerceAtLeast(0)) }, enabled = index > 0) {
                        Icon(Icons.Filled.ChevronLeft, "Previous")
                    }
                    IconButton(onClick = { onNavigate((index + 1).coerceAtMost(files.size - 1)) }, enabled = index < files.size - 1) {
                        Icon(Icons.Filled.ChevronRight, "Next")
                    }
                }
                IconButton(onClick = { FileRepo.shareFiles(context, listOf(file)) }) { Icon(Icons.Filled.Share, "Share") }
            }
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isImage(file) -> AsyncImage(
                    model = file, contentDescription = file.name,
                    modifier = Modifier.fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                )
                text != null -> Text(text!!, modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()))
                else -> Text("Preview not available for this file type", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Error log (v1.8) ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(ErrorLog.read(context)) }
    var status by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Error log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = {
                        val text = entries.joinToString("\n---\n")
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("NOVA Files error log", text))
                        status = "Copied — paste it in a message to share it"
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        ErrorLog.clear(context)
                        entries = emptyList()
                        status = "Log cleared"
                    }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Every crash and failed operation is recorded here with its time, so a problem can be reported with the exact error.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No errors recorded.") }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Text(e, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
