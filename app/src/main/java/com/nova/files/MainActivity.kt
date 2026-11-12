package com.nova.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nova.files.data.FileRepo
import com.nova.files.ui.*
import java.io.File

enum class Screen { HOME, BROWSER, ANALYZER, TRASH, VAULT, APPS }

// FragmentActivity (not ComponentActivity) so the Vault can use BiometricPrompt.
class MainActivity : FragmentActivity() {

    // Classic permission result: the Activity Result API generates request
    // codes >= 65536 and FragmentActivity rejects those, so on API < 30 we
    // use ActivityCompat.requestPermissions with a small code instead.
    // The composable watches this counter to re-check access.
    var permissionResult by mutableStateOf(0)
        private set

    // v1.16: the Vault import picker, also classic. The Activity Result API
    // (rememberLauncherForActivityResult + GetContent) crashed the app on
    // every import for the same reason as the old permission crash —
    // FragmentActivity rejects request codes >= 65536. A small code here
    // keeps it working. The composable watches vaultImportUri.
    var vaultImportUri by mutableStateOf<Uri?>(null)
        private set

    fun clearVaultImportUri() { vaultImportUri = null }

    fun launchVaultImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        try {
            startActivityForResult(intent, REQUEST_VAULT_IMPORT)
        } catch (e: Exception) {
            com.nova.files.data.ErrorLog.log(applicationContext, "Vault", "Couldn't open file picker", e)
        }
    }

    @Deprecated("Classic picker for the Vault import (see above)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VAULT_IMPORT && resultCode == RESULT_OK) {
            vaultImportUri = data?.data
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSIONS) permissionResult++
    }

    companion object {
        const val REQUEST_STORAGE_PERMISSIONS = 1001
        const val REQUEST_VAULT_IMPORT = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.8: record every crash in the in-app error log — the user had no
        // way to see why the app crashed. Chain to the default handler so
        // the crash still shows normally; we only add the record.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                com.nova.files.data.ErrorLog.log(
                    applicationContext, "CRASH",
                    "Uncaught exception on ${thread.name}", e
                )
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, e)
        }
        setContent { NovaApp() }
    }
}

@Composable
fun NovaApp() {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(checkAccess(context)) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var browseDir by remember { mutableStateOf(FileRepo.internalRoot()) }
    var browseCategory by remember { mutableStateOf<String?>(null) }
    // v1.7: copy/move clipboard lives here so it survives tab switches
    // (v1.0–v1.6 kept it inside BrowserScreen and lost it on every tab change).
    var clipboard by remember { mutableStateOf<Pair<List<File>, Boolean>?>(null) }
    // v1.7: light theme (persisted); image viewer keeps its file list for prev/next.
    var darkTheme by remember { mutableStateOf(FileRepo.loadUiPrefs(context).darkTheme) }
    var viewerFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    // v1.8: the in-app error log opens as an overlay, like the viewer.
    var showErrorLog by remember { mutableStateOf(false) }
    val viewerFile = viewerFiles.getOrNull(viewerIndex)

    fun openViewer(file: File, siblings: List<File>) {
        viewerFiles = siblings
        viewerIndex = siblings.indexOf(file).coerceAtLeast(0)
    }

    val activity = context as? MainActivity
    val permissionResult = activity?.permissionResult ?: 0

    // The classic permission result (API < 30) arrives through the Activity;
    // re-check access whenever it changes.
    LaunchedEffect(permissionResult) { hasAccess = checkAccess(context) }

    // System back in the file/image viewer closes the viewer instead of the app.
    BackHandler(enabled = viewerFile != null) { viewerFiles = emptyList() }
    // v1.7: back from a category list returns Home (v1.0–v1.6 exited the app).
    BackHandler(enabled = viewerFile == null && browseCategory != null && screen == Screen.BROWSER) {
        browseCategory = null; screen = Screen.HOME
    }
    // v1.12: back from the error log closes it; back from Analyzer / Trash /
    // Vault returns Home instead of exiting the app.
    BackHandler(enabled = viewerFile == null && showErrorLog) { showErrorLog = false }
    BackHandler(enabled = viewerFile == null && !showErrorLog && browseCategory == null &&
        (screen == Screen.ANALYZER || screen == Screen.TRASH || screen == Screen.VAULT || screen == Screen.APPS)) {
        screen = Screen.HOME
    }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NovaAppContent(
                hasAccess = hasAccess,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        // API < 30: classic requestPermissions with a small
                        // request code — the Activity Result API generates
                        // codes >= 65536, which FragmentActivity rejects.
                        val act = activity ?: return@NovaAppContent
                        ActivityCompat.requestPermissions(
                            act, storagePermissions(), MainActivity.REQUEST_STORAGE_PERMISSIONS
                        )
                    }
                },
                onRecheck = { hasAccess = checkAccess(context) },
                screen = screen, onScreenChange = { screen = it },
                browseDir = browseDir, onBrowseDir = { browseDir = it },
                browseCategory = browseCategory, onBrowseCategory = { browseCategory = it },
                clipboard = clipboard, onClipboardChange = { clipboard = it },
                darkTheme = darkTheme,
                onToggleTheme = {
                    val newTheme = !darkTheme
                    darkTheme = newTheme
                    FileRepo.updateUiPrefs(context) { it.copy(darkTheme = newTheme) }
                },
                viewerFile = viewerFile,
                viewerFiles = viewerFiles, viewerIndex = viewerIndex,
                onViewerIndex = { viewerIndex = it },
                onCloseViewer = { viewerFiles = emptyList() },
                onOpenViewer = ::openViewer,
                showErrorLog = showErrorLog,
                onShowErrorLog = { showErrorLog = it }
            )
        }
    }
}

@Composable
private fun NovaAppContent(
    hasAccess: Boolean, onGrant: () -> Unit, onRecheck: () -> Unit,
    screen: Screen, onScreenChange: (Screen) -> Unit,
    browseDir: File, onBrowseDir: (File) -> Unit,
    browseCategory: String?, onBrowseCategory: (String?) -> Unit,
    clipboard: Pair<List<File>, Boolean>?, onClipboardChange: (Pair<List<File>, Boolean>?) -> Unit,
    darkTheme: Boolean, onToggleTheme: () -> Unit,
    viewerFile: File?, viewerFiles: List<File>, viewerIndex: Int,
    onViewerIndex: (Int) -> Unit, onCloseViewer: () -> Unit,
    onOpenViewer: (File, List<File>) -> Unit,
    showErrorLog: Boolean, onShowErrorLog: (Boolean) -> Unit
) {
    if (!hasAccess) {
        PermissionScreen(onGrant = onGrant, onRecheck = onRecheck)
        return
    }

    viewerFile?.let { file ->
        ViewerScreen(
            file = file, files = viewerFiles, index = viewerIndex,
            onNavigate = onViewerIndex, onBack = onCloseViewer
        )
        return
    }

    if (showErrorLog) {
        ErrorLogScreen(onBack = { onShowErrorLog(false) })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = screen == Screen.HOME,
                    onClick = { onScreenChange(Screen.HOME) },
                    icon = { Icon(Icons.Filled.Home, null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = screen == Screen.BROWSER,
                    onClick = { onScreenChange(Screen.BROWSER); onBrowseCategory(null) },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    label = { Text("Files") }
                )
                NavigationBarItem(
                    selected = screen == Screen.ANALYZER,
                    onClick = { onScreenChange(Screen.ANALYZER) },
                    icon = { Icon(Icons.Filled.Assessment, null) },
                    label = { Text("Storage") }
                )
                NavigationBarItem(
                    selected = screen == Screen.TRASH,
                    onClick = { onScreenChange(Screen.TRASH) },
                    icon = { Icon(Icons.Filled.Delete, null) },
                    label = { Text("Trash") }
                )
                NavigationBarItem(
                    selected = screen == Screen.VAULT,
                    onClick = { onScreenChange(Screen.VAULT) },
                    icon = { Icon(Icons.Filled.Lock, null) },
                    label = { Text("Vault") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onOpenDir = { dir -> onBrowseDir(dir); onBrowseCategory(null); onScreenChange(Screen.BROWSER) },
                    onOpenCategory = { cat -> onBrowseCategory(cat); onBrowseDir(FileRepo.internalRoot()); onScreenChange(Screen.BROWSER) },
                    onOpenFile = onOpenViewer,
                    darkTheme = darkTheme, onToggleTheme = onToggleTheme,
                    onOpenErrorLog = { onShowErrorLog(true) },
                    onOpenApps = { onScreenChange(Screen.APPS) }
                )
                Screen.APPS -> AppManagerScreen(onBack = { onScreenChange(Screen.HOME) })
                Screen.BROWSER -> BrowserScreen(
                    dir = browseDir,
                    category = browseCategory,
                    onNavigate = onBrowseDir,
                    onOpenFile = onOpenViewer,
                    clipboard = clipboard, onClipboardChange = onClipboardChange
                )
                Screen.ANALYZER -> AnalyzerScreen(
                    onOpenFile = onOpenViewer,
                    onOpenDir = { dir -> onBrowseDir(dir); onBrowseCategory(null); onScreenChange(Screen.BROWSER) }
                )
                Screen.TRASH -> TrashScreen()
                Screen.VAULT -> VaultScreen(onOpenFile = { f -> onOpenViewer(f, listOf(f)) })
            }
        }
    }
}

fun checkAccess(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val ok = { p: String -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
        ok(Manifest.permission.READ_MEDIA_IMAGES) && ok(Manifest.permission.READ_MEDIA_VIDEO) &&
            ok(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

fun storagePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onGrant: () -> Unit, onRecheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("NOVA Files", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "To browse, copy and manage your files, NOVA Files needs access to all files on this device. Your files stay on your phone — nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, Modifier.fillMaxWidth()) { Text("Allow file access") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRecheck, Modifier.fillMaxWidth()) { Text("I already allowed it") }
    }
}
