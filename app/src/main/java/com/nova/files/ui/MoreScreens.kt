package com.nova.files.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.nova.files.MainActivity
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
    // v1.17: storageStats() used to run during the first composition (main
    // thread disk I/O) — start empty and fill it in from IO instead.
    var stats by remember { mutableStateOf(StorageStats(0, 0, 0, 0, 0)) }
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
            // v1.17: usedBytes is the USERDATA used and totalBytes is the
            // whole chip — "Used 42 GB of 256 GB" mixed the two, so
            // used + free never added up to the total shown. Compare used
            // against the userdata partition, and show the system slice
            // separately like Home does.
            Text("Used ${formatSizeDecimal(stats.usedBytes)} of ${formatSizeDecimal(stats.partitionTotalBytes)} user storage • Free ${formatSizeDecimal(stats.freeBytes)}")
            if (stats.systemBytes > 0) {
                Text(
                    "System ${formatSizeDecimal(stats.systemBytes)} • ${formatSizeDecimal(stats.totalBytes)} total on device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (scanning) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
        }
        item { Text("Biggest folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(folders.take(12), key = { it.first.absolutePath }) { (dir, size) ->
            ListItem(
                headlineContent = { Text(dir.name) },
                supportingContent = { Text(size?.let { formatSize(it) } ?: "calculating…") },
                leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(NovaRowShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, NovaRowShape)
                    .clickable { onOpenDir(dir) }
            )
        }
        item { Text("Large files (over 50 MB)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(large, key = { it.absolutePath }) { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = { Text("${formatSize(f.length())} • ${f.parentFile?.name}") },
                leadingContent = { Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(NovaRowShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, NovaRowShape)
                    .clickable {
                        if (isImage(f) || f.extensionLower() == "txt") onOpenFile(f, large)
                        else if (!FileRepo.openWithIntent(context, f)) status = "No app can open this file"
                    }
            )
        }
        item {
            // v1.18: Nebula gradient primary button.
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
                modifier = Modifier.fillMaxWidth().nebulaGradient(NovaButtonShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = Color.White
                ),
                shape = NovaButtonShape
            ) { Text(if (dupScanning) "Stop scan" else "Find duplicate files") }
            if (scanStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(scanStatus) }
        }
        items(dups, key = { it.files.firstOrNull()?.absolutePath ?: it.hashCode().toString() }) { group ->
            Card(
                Modifier.fillMaxWidth(),
                shape = NovaCardShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${group.files.size} copies • ${formatSize(group.size)} each", fontWeight = FontWeight.Bold)
                    group.files.forEach { f ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.name, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { dupDeleteTarget = f },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Delete") }
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
                        // v1.15: only remove it from the list if the move
                        // actually succeeded — the old code hid it even on
                        // failure, so the file looked deleted but wasn't.
                        val ok = FileRepo.moveToTrash(context, listOf(target))
                        if (ok) {
                            dups = dups.map { g -> g.copy(files = g.files - target) }.filter { it.files.size > 1 }
                        } else {
                            status = "Couldn't move to Trash"
                        }
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
    // v1.16: permanent deletes need a confirmation — "Empty all" and the
    // per-item Delete used to wipe files on a single mis-tap, with no undo.
    var emptyConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun load() { scope.launch { FileRepo.purgeOldTrash(context); trash = FileRepo.listTrash(context) } }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = { Text("Trash") },
                actions = {
                    TextButton(
                        onClick = { emptyConfirm = true },
                        enabled = trash.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Empty all") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Deleted files stay here for 30 days, then are removed. Restore any time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(12.dp))
            if (trash.isEmpty()) {
                NebulaEmptyState(Icons.Filled.Delete, "Trash is empty")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trash, key = { it.absolutePath }) { f ->
                        ListItem(
                            // v1.16: trash names are <stamp>_<index>_<name>
                            // (v1.15) or <stamp>_<name> (v1.14) — strip both
                            // prefixes, or every item showed "0_photo.jpg".
                            headlineContent = { Text(FileRepo.trashDisplayName(f.name)) },
                            supportingContent = { Text(if (f.isDirectory) "Folder" else formatSize(f.length())) },
                            leadingContent = { Icon(Icons.Filled.Delete, null) },
                            trailingContent = {
                                Row {
                                    // v1.16: report the real result — the old
                                    // code always said "Restored" even when
                                    // the copy failed and the file stayed here.
                                    TextButton(onClick = { scope.launch {
                                        val ok = FileRepo.restoreFromTrash(context, f)
                                        status = if (ok) "Restored" else "Couldn't restore — the original location may be unavailable"
                                        load()
                                    } }) { Text("Restore") }
                                    TextButton(
                                        onClick = { deleteTarget = f },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) { Text("Delete") }
                                }
                            },
                            modifier = Modifier.clip(NovaRowShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, NovaRowShape)
                        )
                    }
                }
            }
        }
    }

    if (emptyConfirm) {
        AlertDialog(
            onDismissRequest = { emptyConfirm = false },
            title = { Text("Empty the Trash?") },
            text = { Text("This permanently deletes everything in the Trash (${trash.size} items). This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { FileRepo.emptyTrash(context); status = "Trash emptied"; load() }
                emptyConfirm = false
            }) { Text("Empty Trash") } },
            dismissButton = { TextButton(onClick = { emptyConfirm = false }) { Text("Cancel") } }
        )
    }
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete permanently?") },
            text = { Text("“${FileRepo.trashDisplayName(target.name)}” will be permanently deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { FileRepo.deleteFromTrash(context, target) }; load() }
                deleteTarget = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ---------- Vault ----------
/** Fingerprint availability, checked fresh each time (v1.17) — the old
 *  code computed it once with remember and it went stale. */
private fun isBiometricAvailable(context: Context): Boolean {
    return try {
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
    // v1.19: PBKDF2 + migration run off the main thread; the Unlock
    // button is disabled while they run.
    var busy by remember { mutableStateOf(false) }
    var moveOutTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    // v1.9: confirm field for PIN setup + the Change PIN dialog state.
    var confirmPin by remember { mutableStateOf("") }
    var showChangePin by remember { mutableStateOf(false) }
    // v1.10: the Remove PIN dialog state.
    var showRemovePin by remember { mutableStateOf(false) }

    // v1.16: the import picker is the classic startActivityForResult flow
    // on MainActivity — the Activity Result API crashed the app on every
    // import (FragmentActivity rejects its >= 65536 request codes).
    val mainActivity = LocalContext.current as? MainActivity
    LaunchedEffect(mainActivity?.vaultImportUri) {
        val uri = mainActivity?.vaultImportUri ?: return@LaunchedEffect
        mainActivity.clearVaultImportUri()
        scope.launch {
            // v1.19: the import encrypts the file into the vault
            // (FileRepo.importToVault) — a failed import leaves nothing.
            status = withContext(Dispatchers.IO) { FileRepo.importToVault(context, uri) }
            files = withContext(Dispatchers.IO) { FileRepo.vaultDir(context).listFiles()?.toList() ?: emptyList() }
        }
    }

    fun load() { scope.launch { files = withContext(Dispatchers.IO) { FileRepo.vaultDir(context).listFiles()?.toList() ?: emptyList() } } }

    // v1.19: leaving the Vault screen locks it — the in-memory master
    // key is wiped, so the files can't be opened again without the PIN.
    DisposableEffect(Unit) { onDispose { FileRepo.lockVault() } }

    // v1.9: one shared fingerprint flow for unlock AND for "Forgot PIN?"
    // reset. The prompt now names BIOMETRIC_STRONG explicitly (the
    // recommended form with a negative button), and cancelling it or
    // tapping "Use PIN" no longer shows a scary error message.
    //
    // v1.19: the prompt ALWAYS carries a CryptoObject — a Keystore
    // cipher that wraps/unwraps the vault master key. A fingerprint
    // without the cipher cannot open the vault (there is no PIN to
    // derive the key from), so the plain-auth path is gone: every
    // caller builds its cipher first (FileRepo.newKeystore*Cipher) and
    // the success callback receives the authenticated cipher to finish
    // the wrap/unwrap. Without a stored Keystore wrap the fingerprint
    // buttons are hidden and the UI says to use the PIN.
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
    // v1.17: availability was computed once per composition and went stale
    // (e.g. after enrolling a fingerprint in Settings while the app was
    // open). Recompute whenever the lock screen shows again, and re-check
    // at the moment the button is tapped.
    val biometricAvailable = remember(unlocked) { isBiometricAvailable(context) }
    fun launchFingerprint(
        cryptoObject: androidx.biometric.BiometricPrompt.CryptoObject,
        onCipher: (javax.crypto.Cipher) -> Unit
    ) {
        if (!isBiometricAvailable(context)) {
            error = "Fingerprint is not set up — use your PIN"
            return
        }
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
                            val cipher = result.cryptoObject?.cipher
                            if (cipher != null) onCipher(cipher)
                            else error = "Fingerprint worked, but the vault key couldn't be used — use your PIN"
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
                    .build(),
                cryptoObject
            )
        } catch (t: Throwable) {
            ErrorLog.log(context, "Vault", "Fingerprint unlock failed", t)
            error = "Fingerprint unavailable (${t.javaClass.simpleName}) — use your PIN"
        }
    }

    // v1.19: fingerprint unlock needs a stored Keystore wrap (created by
    // "Enable fingerprint unlock" after a PIN unlock). Until then the
    // fingerprint buttons stay hidden — there is no key to unwrap.
    val hasKeystoreWrap = FileRepo.vaultHasKeystoreWrap(context)

    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            // v1.18: Nebula hero — lock icon in a gradient circle.
            Box(
                Modifier.size(88.dp).background(NebulaGradientBrush, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Text(if (setupMode) "Set a vault PIN" else "Enter vault PIN", style = MaterialTheme.typography.titleLarge)
            if (setupMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Choose your own 4–6 digit PIN. There is no default PIN — nobody can open the vault without the PIN you set here. Your files are encrypted with this PIN: if you forget it and never enabled fingerprint unlock, the files can't be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("PIN (4–6 digits)") },
                singleLine = true,
                shape = NovaButtonShape,
                visualTransformation = PasswordVisualTransformation()
            )
            if (setupMode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    shape = NovaButtonShape,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            if (error.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (pin.length < 4) { error = "PIN must be at least 4 digits"; return@Button }
                    if (setupMode && pin != confirmPin) { error = "PINs do not match"; return@Button }
                    val enteredPin = pin
                    val wasSetup = setupMode
                    busy = true; error = ""
                    scope.launch {
                        // v1.19: PIN setup/unlock + migration run on IO
                        // (PBKDF2 + file encryption must never run on the
                        // main thread). setupVaultPin also creates the
                        // master key and wraps it under the new PIN; on a
                        // "Forgot PIN?" reset it re-wraps the key the
                        // fingerprint just recovered, so the files survive.
                        val result = withContext(Dispatchers.IO) {
                            if (wasSetup) {
                                if (!FileRepo.setupVaultPin(context, enteredPin)) null
                                else FileRepo.VaultUnlockResult(
                                    true,
                                    FileRepo.migrateLegacyVaultFiles(context)
                                )
                            } else {
                                FileRepo.unlockVaultWithPin(context, enteredPin)
                            }
                        }
                        busy = false
                        if (result?.ok == true) {
                            unlocked = true; setupMode = false; pin = ""; confirmPin = ""
                            load()
                            if (result.migrationFailures.isNotEmpty()) {
                                status = "Couldn't encrypt ${result.migrationFailures.size} file(s): ${result.migrationFailures.take(3).joinToString(", ")} — they stay readable only inside the vault"
                            }
                        } else if (wasSetup) {
                            error = "Couldn't set up vault encryption — try again"
                        } else {
                            error = "Wrong PIN"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().nebulaGradient(NovaButtonShape),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = Color.White
                ),
                shape = NovaButtonShape
            ) { Text(if (busy) "Unlocking…" else if (setupMode) "Create vault" else "Unlock") }
            if (!setupMode && biometricAvailable && hasKeystoreWrap && activity != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val cipher = FileRepo.newKeystoreDecryptCipher(context)
                        if (cipher == null) { error = "Fingerprint unavailable — use your PIN"; return@OutlinedButton }
                        launchFingerprint(androidx.biometric.BiometricPrompt.CryptoObject(cipher)) { authed ->
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    FileRepo.unlockWithKeystoreCipher(context, authed)
                                }
                                if (result.ok) {
                                    unlocked = true; pin = ""; load()
                                    if (result.migrationFailures.isNotEmpty()) {
                                        status = "Couldn't encrypt ${result.migrationFailures.size} file(s): ${result.migrationFailures.take(3).joinToString(", ")} — they stay readable only inside the vault"
                                    }
                                } else error = "Fingerprint worked, but the vault couldn't be opened — use your PIN"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = NovaButtonShape
                ) { Text("Unlock with fingerprint") }
            }
            if (!setupMode && biometricAvailable && !hasKeystoreWrap && activity != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unlock once with your PIN to enable fingerprint unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!setupMode) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    // v1.9: "Forgot PIN?" — prove it is you with a fingerprint,
                    // then set a new PIN. This is the way out when a PIN was
                    // set during an earlier test and forgotten.
                    //
                    // v1.19: the reset only works when fingerprint unlock was
                    // enabled (a Keystore-wrapped master key exists) — the
                    // fingerprint unwraps the key, and the new PIN re-wraps
                    // it, so the files survive. Without the wrap the files
                    // are unrecoverable without the PIN, and the reset must
                    // not orphan them by setting a new PIN anyway.
                    if (biometricAvailable && hasKeystoreWrap && activity != null) {
                        val cipher = FileRepo.newKeystoreDecryptCipher(context)
                        if (cipher == null) {
                            error = "Fingerprint unavailable — use your PIN"
                        } else {
                            launchFingerprint(androidx.biometric.BiometricPrompt.CryptoObject(cipher)) { authed ->
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        FileRepo.unlockWithKeystoreCipher(context, authed)
                                    }
                                    if (result.ok) {
                                        setupMode = true; pin = ""; confirmPin = ""; error = ""
                                    } else {
                                        error = "Fingerprint worked, but the vault couldn't be opened — use your PIN"
                                    }
                                }
                            }
                        }
                    } else if (!biometricAvailable) {
                        error = "Fingerprint is not set up, so the PIN cannot be reset from here. Clear the app data in Settings to reset the vault — this also deletes the vault files."
                    } else {
                        error = "Fingerprint unlock was never enabled, so the vault can't be recovered without the PIN — the files are encrypted. To start over, clear the app data in Settings (this deletes the vault files)."
                    }
                }) { Text("Forgot PIN?") }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = { Text("Vault") },
                actions = {
                    TextButton(onClick = { showChangePin = true }) { Text("Change PIN") }
                    TextButton(
                        onClick = { showRemovePin = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Remove PIN") }
                    TextButton(onClick = { mainActivity?.launchVaultImport() }) { Text("Import") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Files in the vault are encrypted (AES-256) and stored in private app storage — other apps cannot see them, and they can't be read without your PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasKeystoreWrap && biometricAvailable && activity != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val cipher = FileRepo.newKeystoreEncryptCipher()
                        if (cipher == null) { status = "Fingerprint unavailable"; return@OutlinedButton }
                        launchFingerprint(androidx.biometric.BiometricPrompt.CryptoObject(cipher)) { authed ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { FileRepo.finishKeystoreWrap(context, authed) }
                                status = if (ok) "Fingerprint unlock enabled" else "Couldn't enable fingerprint unlock"
                            }
                        }
                    },
                    shape = NovaButtonShape
                ) { Text("Enable fingerprint unlock") }
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(12.dp))
            if (files.isEmpty()) {
                NebulaEmptyState(Icons.Filled.Lock, "Vault is empty. Tap Import to add files.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            supportingContent = { Text(formatSize(f.length())) },
                            leadingContent = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { moveOutTarget = f }) { Text("Move out") }
                                    TextButton(
                                        onClick = { deleteTarget = f },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) { Text("Delete") }
                                }
                            },
                            // v1.17: only images and text open inside the
                            // Vault — the old code silently did nothing for
                            // every other type. Other types must be moved
                            // out first (opening them in another app would
                            // hand a private vault file to that app).
                            modifier = Modifier.clip(NovaRowShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, NovaRowShape)
                                .clickable {
                                    if (isImage(f) || f.extensionLower() == "txt") {
                                        // v1.19: the viewer reads a plain File,
                                        // so an encrypted vault file is
                                        // decrypted to a private temp file
                                        // first (deleted when the viewer
                                        // closes); a damaged file refuses
                                        // to open instead of showing junk.
                                        scope.launch {
                                            val opened = withContext(Dispatchers.IO) {
                                                FileRepo.openVaultFileForViewing(context, f)
                                            }
                                            if (opened != null) onOpenFile(opened)
                                            else status = "Couldn't open this file — it may be damaged"
                                        }
                                    } else status = "Only images and text open inside the Vault — move it out to open it elsewhere"
                                }
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
                    // v1.19: moving out decrypts the file — it leaves the
                    // vault as a normal file. The vault original is kept
                    // on failure.
                    val ok = FileRepo.moveVaultFileOut(context, target, dest)
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
                        // v1.17: report the real result — the old code said
                        // "Deleted from vault" even when the delete failed.
                        val ok = withContext(Dispatchers.IO) { FileRepo.deleteRecursively(target) }
                        status = if (ok) "Deleted from vault" else "Couldn't delete from vault"
                        deleteTarget = null; load()
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
                        shape = NovaButtonShape,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); changeError = null },
                        label = { Text("New PIN (4–6 digits)") }, singleLine = true,
                        shape = NovaButtonShape,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmNewPin, onValueChange = { confirmNewPin = it.filter { c -> c.isDigit() }.take(6); changeError = null },
                        label = { Text("Confirm new PIN") }, singleLine = true,
                        shape = NovaButtonShape,
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
                            // v1.19: only the master-key wrap changes — no
                            // file is re-encrypted. Runs on IO (PBKDF2).
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    FileRepo.changeVaultPin(context, currentPin, newPin)
                                }
                                if (ok) {
                                    showChangePin = false
                                    status = "PIN changed"
                                } else changeError = "Current PIN is wrong or the vault couldn't be opened"
                            }
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
                    Text("The vault will lock, and next time you open it you will be asked to set a new PIN. Files already in the vault stay where they are — they are decrypted back to normal files first, so they stay readable.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = currentPin, onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(6); removeError = null },
                        label = { Text("Current PIN") }, singleLine = true,
                        shape = NovaButtonShape,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    removeError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // v1.19: removing the PIN first decrypts the vault
                    // back to plaintext (otherwise the files would be
                    // unreadable forever). A failure keeps the PIN.
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            FileRepo.removeVaultPin(context, currentPin)
                        }
                        if (result.ok) {
                            showRemovePin = false
                            unlocked = false
                            pin = ""; confirmPin = ""
                            setupMode = true
                            error = ""
                            status = ""
                        } else if (result.failedFiles.isNotEmpty()) {
                            removeError = "Couldn't decrypt ${result.failedFiles.size} file(s): ${result.failedFiles.take(3).joinToString(", ")} — the PIN stays"
                        } else {
                            removeError = result.error ?: "Couldn't remove the PIN"
                        }
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
    onNavigate: (Int) -> Unit = {}, onBack: () -> Unit,
    // v1.17: vault files must not be shared — sharing hands the private
    // file to another app, which defeats the Vault.
    isVault: Boolean = false
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    // v1.7: pinch-to-zoom for images (v1.0–v1.6 showed them fixed-size).
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        // v1.17: pan was unbounded — a zoomed photo could be pushed
        // off-screen with no way back except leaving the file.
        offset = if (scale <= 1f) Offset.Zero else {
            val bound = 1000f * (scale - 1f)
            Offset(
                (offset.x + panChange.x).coerceIn(-bound, bound),
                (offset.y + panChange.y).coerceIn(-bound, bound)
            )
        }
    }
    LaunchedEffect(file) {
        scale = 1f; offset = Offset.Zero
        if (file.extensionLower() == "txt") {
            text = withContext(Dispatchers.IO) {
                // v1.16: stream only the first 200 KB — readText() loaded
                // the whole file first, so a 200 MB log OOM'd the app.
                try {
                    file.inputStream().bufferedReader().use { r ->
                        val buf = CharArray(200_000)
                        val read = r.read(buf)
                        if (read < 0) "" else String(buf, 0, read)
                    }
                } catch (e: Exception) { "Could not read file." }
            }
        } else text = null
    }
    Scaffold(topBar = {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
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
                if (!isVault) {
                    IconButton(onClick = { if (!FileRepo.shareFiles(context, listOf(file))) status = "Couldn't share this file" }) { Icon(Icons.Filled.Share, "Share") }
                }
            }
        )
    }) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isImage(file) -> AsyncImage(
                    model = file, contentDescription = file.name,
                    modifier = Modifier.fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                )
                text != null -> Text(text!!, modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()))
                else -> NebulaEmptyState(Icons.Filled.InsertDriveFile, "Preview not available for this file type")
            }
            // v1.17: the viewer never showed its status text (e.g. a failed
            // share), so failures were invisible.
            if (status.isNotBlank()) {
                Text(
                    status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = { Text("Error log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        onClick = {
                            val text = entries.joinToString("\n---\n")
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NOVA Files error log", text))
                            status = "Copied — paste it in a message to share it"
                        },
                        // v1.17: Copy on an empty log copied nothing and
                        // still said "Copied".
                        enabled = entries.isNotEmpty()
                    ) { Text("Copy") }
                    TextButton(
                        onClick = {
                            ErrorLog.clear(context)
                            entries = emptyList()
                            status = "Log cleared"
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Clear") }
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
                NebulaEmptyState(Icons.Filled.CheckCircle, "No errors recorded.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Index keys: two entries can have identical text (same
                    // second, same message), and duplicate keys crash the list.
                    itemsIndexed(entries, key = { index, _ -> index }) { _, e ->
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = NovaCardShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(e, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
