package com.nova.files.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.files.data.FileRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Shared folder picker (was private inside BrowserScreen; the Vault's
// "Move out" needs the same chooser).
@Composable
fun FolderPickerDialog(
    startDir: File, title: String, confirmLabel: String,
    onDismiss: () -> Unit, onPick: (File) -> Unit
) {
    var dir by remember { mutableStateOf(startDir) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(dir) {
        subdirs = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(dir.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (dir.parentFile != null && dir != FileRepo.internalRoot()) {
                    TextButton(onClick = { dir = dir.parentFile!! }) { Text("↑ Parent folder") }
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (subdirs.isEmpty()) Text("No subfolders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    subdirs.forEach { d ->
                        TextButton(onClick = { dir = d }, modifier = Modifier.fillMaxWidth()) {
                            Text(d.name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(dir) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
