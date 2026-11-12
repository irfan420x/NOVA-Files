package com.nova.files.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class SortMode { NAME, SIZE, DATE, TYPE }

data class StorageStats(
    val totalBytes: Long,          // whole flash chip (device total)
    val freeBytes: Long,           // free space on the userdata partition
    val usedBytes: Long,           // userdata used (partition total - free,
                                    // matches Settings / Files by Google)
    val systemBytes: Long,         // device total - userdata partition total
    val partitionTotalBytes: Long  // userdata partition total (StatFs)
)

data class StorageVolumeInfo(
    val name: String,       // e.g. "USB drive" / "USB storage (ABCD-1234)"
    val dir: File,          // mount root, e.g. /storage/ABCD-1234
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long
)
data class CategoryCounts(val images: Long, val videos: Long, val audio: Long, val documents: Long, val apks: Long, val downloads: Long)
data class DuplicateGroup(val size: Long, val files: List<File>)

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    // v1.17: 1,024,000 bytes divided out to "1000.0 KB" — promote once
    // more so it reads "1.0 MB" like every other file manager.
    if (v >= 999.95 && i < units.size - 1) { v /= 1024; i++ }
    // Locale.US: %.1f in some locales renders "1,0" or Bengali digits.
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

// Decimal (1000-based) sizes, the way stock file managers / Settings show them:
// 256 GB chip = 256,000,000,000 bytes, not 256 GiB.
fun formatSizeDecimal(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
    // v1.17: same boundary promotion as formatSize ("1000.0 KB" -> "1.0 MB").
    if (v >= 999.95 && i < units.size - 1) { v /= 1000; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

// v1.17: a leading dot is NOT an extension — ".gitignore" used to report
// extension "gitignore" (and fileKind "GITIGNORE"). Only a dot after the
// first character separates an extension.
fun File.extensionLower(): String {
    val idx = name.lastIndexOf('.')
    if (idx <= 0) return ""
    return name.substring(idx + 1).lowercase()
}

/** File name without its extension ("my.backup.zip" -> "my.backup",
 *  ".gitignore" -> ".gitignore"). Kotlin's nameWithoutExtension returns ""
 *  for leading-dot files, which made a compress of ".gitignore" target
 *  a file called ".zip". */
fun baseNameWithoutExt(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}

/** Strip the archive suffix for the extract destination folder:
 *  "my.backup.zip" -> "my.backup" (not "my"), "file.tar.gz" -> "file". */
fun archiveBaseName(name: String): String {
    val lower = name.lowercase()
    val stripped = when {
        lower.endsWith(".tar.gz") -> name.dropLast(7)
        lower.endsWith(".tgz") -> name.dropLast(4)
        lower.endsWith(".tar") -> name.dropLast(4)
        lower.endsWith(".gz") -> name.dropLast(3)
        else -> name.substringBeforeLast('.', name)
    }
    return stripped.takeIf { it.isNotBlank() } ?: "extracted"
}

/** RAR5 magic: "Rar!\x1A\x07\x01\x00" — junrar only reads RAR4
 *  ("Rar!\x1A\x07\x00"), so RAR5 must be detected and refused honestly
 *  instead of offered and failed silently. */
fun isRar5(f: File): Boolean {
    return try {
        FileInputStream(f).use { ins ->
            val magic = ByteArray(8)
            val read = ins.read(magic)
            read == 8 && magic[0] == 0x52.toByte() && magic[1] == 0x61.toByte() &&
                magic[2] == 0x72.toByte() && magic[3] == 0x21.toByte() &&
                magic[4] == 0x1A.toByte() && magic[5] == 0x07.toByte() &&
                magic[6] == 0x01.toByte() && magic[7] == 0x00.toByte()
        }
    } catch (e: Exception) { false }
}

fun isImage(f: File) = f.extensionLower() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
fun isVideo(f: File) = f.extensionLower() in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
fun isAudio(f: File) = f.extensionLower() in setOf("mp3", "wav", "flac", "ogg", "m4a", "aac")
fun isDocument(f: File) = f.extensionLower() in setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv", "epub")
fun isApk(f: File) = f.extensionLower() == "apk"
fun isArchive(f: File) = f.extensionLower() in setOf("zip", "rar", "7z", "tar", "gz")

/** Human-readable file type (v1.8): shown in file rows, Details and the
 * long-press menu, so the app says what each file is instead of treating
 * every file the same. */
fun fileKind(f: File): String = when {
    f.isDirectory -> "Folder"
    isImage(f) -> "Image"
    isVideo(f) -> "Video"
    isAudio(f) -> "Audio"
    isApk(f) -> "APK"
    isArchive(f) -> "Archive"
    isDocument(f) -> "Document"
    f.extensionLower().isNotEmpty() -> f.extensionLower().uppercase()
    else -> "File"
}

object FileRepo {

    // Document MIME types, shared by category counts and the MediaStore scan.
    private val docMimes = arrayOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain", "text/csv",
        "application/epub+zip"
    )

    fun internalRoot(): File = Environment.getExternalStorageDirectory()

    fun trashDir(context: Context): File = File(context.filesDir, "trash").apply { mkdirs() }
    fun vaultDir(context: Context): File = File(context.filesDir, "vault").apply { mkdirs() }

    // Whole flash chip size. v1.1 tried /sys/block/*/size, but on a stock phone
    // SELinux denies third-party apps reading /sys/block — so it silently fell
    // back to the userdata partition (242.3 GB) and the numbers stayed wrong.
    // Senior fix: sum the real mounted block partitions from /proc/mounts
    // (world-readable) via StatFs, deduped by device and by size, then snap to
    // the nominal chip capacity the way OEM apps report it (128/256/512 GB).
    fun deviceTotalBytes(partitionTotal: Long): Long {
        var best = partitionTotal
        // 1) /sys/block — works on some devices / rooted phones, blocked on stock.
        for (dev in listOf("mmcblk0", "sda", "sdb", "nvme0n1")) {
            try {
                val sectors = File("/sys/block/$dev/size").readText().trim().toLongOrNull() ?: continue
                val bytes = sectors * 512L
                if (bytes > best && bytes < 4L * 1024 * 1024 * 1024 * 1024) best = bytes
            } catch (e: Exception) { /* not readable on this device */ }
        }
        // 2) Sum of mounted block partitions (system, vendor, product, data,
        //    metadata, persist, ...). Skip virtual mounts: tmpfs, sdcardfs/FUSE,
        //    /apex loop mounts, /storage and /mnt (emulated storage duplicates).
        try {
            var sum = 0L
            val seenSources = mutableSetOf<String>()
            val seenTotals = mutableSetOf<Long>()
            File("/proc/mounts").forEachLine { line ->
                val parts = line.split(" ")
                if (parts.size < 3) return@forEachLine
                val source = parts[0]
                val mount = parts[1]
                if (!source.startsWith("/dev/block/")) return@forEachLine
                if (mount.startsWith("/apex") || mount.startsWith("/storage") ||
                    mount.startsWith("/mnt") || mount.startsWith("/data/media")
                ) return@forEachLine
                if (!seenSources.add(source)) return@forEachLine
                try {
                    val st = StatFs(mount)
                    val total = st.blockCountLong * st.blockSizeLong
                    // Same total = same filesystem mounted twice (e.g. / and /system
                    // on system-as-root devices) — count it once.
                    if (total > 0 && seenTotals.add(total)) sum += total
                } catch (e: Exception) { /* mount not stat-able */ }
            }
            if (sum > best) best = sum
        } catch (e: Exception) { /* /proc/mounts not readable */ }
        // 3) Snap to the nominal capacity: a 256 GB chip never measures exactly
        //    256,000,000,000 bytes once boot/recovery/reserved space is excluded.
        return snapToNominal(best, longArrayOf(64, 128, 256, 512, 1024, 2048))
    }

    // Snap a measured size up to the nearest nominal capacity when it sits
    // within 8% below one — the way OEM apps report chip / stick sizes.
    private fun snapToNominal(bytes: Long, candidates: LongArray): Long {
        val gb = 1_000_000_000L
        for (cap in candidates) {
            val capBytes = cap * gb
            if (bytes in (capBytes * 92 / 100) until capBytes) return capBytes
        }
        return bytes
    }

    // ---- v1.6: removable volumes (USB OTG / memory cards) ----
    // v1.0–v1.5 only ever looked at internal storage, so a plugged-in USB
    // drive or memory card was invisible. Volumes come from StorageManager
    // (friendly name + mount state), with getExternalFilesDirs as fallback.
    fun listStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val out = mutableListOf<StorageVolumeInfo>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (vol in sm.storageVolumes) {
                if (vol.isPrimary || !vol.isRemovable) continue
                if (vol.state != Environment.MEDIA_MOUNTED) continue
                val dir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    runCatching { vol.directory }.getOrNull()
                } else null
                if (dir == null || !dir.exists() || !dir.canRead()) continue
                volumeInfo(vol.getDescription(context), dir)?.let { out.add(it) }
            }
        } catch (e: Exception) { /* StorageManager unavailable */ }
        if (out.isNotEmpty()) return out
        try {
            for (f in context.getExternalFilesDirs(null)) {
                if (f == null) continue
                // <volume>/Android/data/<pkg>/files -> walk 4 parents up to <volume>
                var root: File? = f
                repeat(4) { root = root?.parentFile }
                val dir = root ?: continue
                if (dir == internalRoot() || dir.path.startsWith("/storage/emulated")) continue
                if (!dir.exists() || !dir.canRead()) continue
                volumeInfo("USB storage (${dir.name})", dir)?.let { out.add(it) }
            }
        } catch (e: Exception) { /* no external dirs */ }
        return out
    }

    private fun volumeInfo(name: String, dir: File): StorageVolumeInfo? {
        return try {
            val st = StatFs(dir.path)
            val rawTotal = st.blockCountLong * st.blockSizeLong
            val total = snapToNominal(
                rawTotal,
                longArrayOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048)
            )
            val free = st.availableBlocksLong * st.blockSizeLong
            // v1.16: "used" must be computed from the REAL total, not the
            // snapped one — snapping 59 GB up to 64 GB while free stayed
            // real overstated the used space by the snap gap.
            StorageVolumeInfo(name, dir, total, free, (rawTotal - free).coerceAtLeast(0L))
        } catch (e: Exception) { null }
    }

    // v1.17: suspend + IO — the old call ran StatFs and read /proc/mounts
    // on the main thread during the first composition (Home + Analyzer).
    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        val stat = StatFs(internalRoot().path)
        val partitionTotal = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val deviceTotal = deviceTotalBytes(partitionTotal)
        val system = (deviceTotal - partitionTotal).coerceAtLeast(0L)
        // v1.15: "used" is now the userdata used (partition total - free),
        // which is what Settings and Files by Google show (~42 GB on this
        // phone). The old headline (device total - free) counted the system
        // partition too, so it read ~14 GB higher than Settings.
        StorageStats(deviceTotal, free, partitionTotal - free, system, partitionTotal)
    }

    suspend fun listFiles(dir: File, sort: SortMode, query: String, showHidden: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            val raw = dir.listFiles()?.toList() ?: emptyList()
            var list = raw.filter { showHidden || !it.name.startsWith(".") }
            if (query.isNotBlank()) {
                list = list.filter { it.name.contains(query, ignoreCase = true) }
            }
            when (sort) {
                SortMode.NAME -> list.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                SortMode.SIZE -> list.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { if (it.isDirectory) 0L else it.length() })
                SortMode.DATE -> list.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
                SortMode.TYPE -> list.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.extensionLower() }.thenBy { it.name.lowercase() })
            }
        }

    /** Apply the user's sort to a category list (MediaStore order is always
     *  date-desc, so the sort menu did nothing in category views). */
    fun sortCategoryFiles(list: List<File>, sort: SortMode): List<File> = when (sort) {
        SortMode.NAME -> list.sortedBy { it.name.lowercase() }
        SortMode.SIZE -> list.sortedByDescending { if (it.isDirectory) 0L else it.length() }
        SortMode.DATE -> list.sortedByDescending { it.lastModified() }
        SortMode.TYPE -> list.sortedWith(compareBy<File> { it.extensionLower() }.thenBy { it.name.lowercase() })
    }

    suspend fun categoryCounts(context: Context): CategoryCounts = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // NOTE: MediaStore rejects "COUNT(*)" as a projection on Android 13/14
        // (Invalid column COUNT(*)) — that is why v1 showed 0 for Images/Videos/
        // Audio. Querying _ID and taking cursor.count is the reliable way.
        fun rowCount(uri: android.net.Uri, selection: String? = null, args: Array<String>? = null): Long {
            return try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { it.count.toLong() } ?: 0L
            } catch (e: Exception) { 0L }
        }
        val images = rowCount(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videos = rowCount(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        val audio = rowCount(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        // Documents / APKs: v1 only scanned the Download folder, so files in
        // Documents, WhatsApp, etc. were never counted. MediaStore.Files sees
        // the whole storage.
        val filesUri = MediaStore.Files.getContentUri("external")
        val docSel = "mime_type IN (${docMimes.joinToString(",") { "?" }})"
        val docs = rowCount(filesUri, docSel, docMimes)
        val apks = rowCount(filesUri, "mime_type=?", arrayOf("application/vnd.android.package-archive"))
        val dl = File(internalRoot(), "Download")
        val downloads = dl.listFiles()?.count { it.isFile }?.toLong() ?: 0L
        CategoryCounts(images, videos, audio, docs, apks, downloads)
    }

    // ---- v1.5: fast MediaStore-backed categories + recursive search + recents ----

    // Category file lists via MediaStore (indexed, instant) instead of walking
    // the whole storage on every open (v1.0–v1.4 scanCategory). Falls back to
    // the walk when MediaStore fails.
    suspend fun listCategoryFiles(context: Context, category: String): List<File> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED)
            fun queryFiles(uri: android.net.Uri, selection: String? = null, args: Array<String>? = null): List<File> {
                val out = mutableListOf<File>()
                // v1.13: NO LIMIT in the sort order — MediaStore rejects the
                // LIMIT token ("Invalid token LIMIT"), so every category fell
                // back to a slow whole-storage walk. Cap with .take(500).
                resolver.query(uri, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext()) {
                        val path = if (dataIdx >= 0) c.getString(dataIdx) else null
                        if (!path.isNullOrBlank()) {
                            val f = File(path)
                            if (f.isFile) out.add(f)
                        }
                        // v1.17: stop walking the cursor once we have the
                        // cap — the old code scanned the whole table first
                        // and only then took 500.
                        if (out.size >= 500) break
                    }
                }
                return out.take(500)
            }
            val filesUri = MediaStore.Files.getContentUri("external")
            val docSel = "mime_type IN (${docMimes.joinToString(",") { "?" }})"
            when (category) {
                "images" -> queryFiles(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                "videos" -> queryFiles(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                "audio" -> queryFiles(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                "documents" -> queryFiles(filesUri, docSel, docMimes)
                "apks" -> queryFiles(filesUri, "mime_type=?", arrayOf("application/vnd.android.package-archive"))
                "downloads" -> queryFiles(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                else -> emptyList()
            }
        } catch (e: Exception) {
            walkCategory(internalRoot(), category)
        }
    }

    // Old whole-storage walk, kept only as the MediaStore fallback.
    private fun walkCategory(root: File, category: String): List<File> {
        val out = mutableListOf<File>()
        val match: (File) -> Boolean = when (category) {
            "images" -> { f -> isImage(f) }
            "videos" -> { f -> isVideo(f) }
            "audio" -> { f -> isAudio(f) }
            "documents" -> { f -> isDocument(f) }
            "apks" -> { f -> isApk(f) }
            "downloads" -> { f -> f.parentFile?.name == "Download" }
            else -> { _ -> false }
        }
        root.walkTopDown()
            .onEnter { d -> d.name != "Android" && !d.name.startsWith(".") }
            .forEach { f -> if (f.isFile && match(f)) { out.add(f); if (out.size >= 500) return out.sortedByDescending { it.lastModified() } } }
        return out.sortedByDescending { it.lastModified() }
    }

    // Recursive search under dir (v1.0–v1.4 only filtered the current folder).
    // Skips Android/ (app-private data) and hidden dirs unless showHidden.
    suspend fun searchFiles(dir: File, query: String, showHidden: Boolean, limit: Int = 300): List<File> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<File>()
            var checked = 0
            // Plain for loop: ensureActive() only resolves in the
            // withContext scope, not inside a forEach lambda.
            for (f in dir.walkTopDown()
                .onEnter { d -> d.name != "Android" && (showHidden || !d.name.startsWith(".")) }) {
                if (++checked % 500 == 0) ensureActive()
                if (f.isFile && f.name.contains(query, ignoreCase = true)) {
                    out.add(f)
                    if (out.size >= limit) return@withContext out.sortedByDescending { it.lastModified() }
                }
            }
            out.sortedByDescending { it.lastModified() }
        }

    // Most recently modified files across shared storage (Home "Recent files").
    suspend fun recentFiles(context: Context, limit: Int = 20): List<File> = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<File>()
            // v1.13: NO LIMIT in the sort order — MediaStore rejects the
            // LIMIT token, so this query threw and Recent files showed
            // nothing at all. Cap with .take(limit) instead.
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATA),
                null, null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val path = if (idx >= 0) c.getString(idx) else null
                    if (path.isNullOrBlank()) continue
                    if (path.split("/").any { it.startsWith(".") }) continue
                    val f = File(path)
                    if (f.isFile) out.add(f)
                    // v1.17: the cursor is date-desc, so the first `limit`
                    // valid files ARE the answer — don't scan the whole table.
                    if (out.size >= limit) break
                }
            }
            out.take(limit)
        } catch (e: Exception) { emptyList() }
    }

    // Open a file with the system's chooser. Returns false when no app can
    // open it (callers show that instead of failing silently).
    fun openWithIntent(context: Context, f: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "com.nova.files.provider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extensionLower()) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
            true
        } catch (e: Exception) { false }
    }

    suspend fun folderSize(dir: File): Long = withContext(Dispatchers.IO) {
        var total = 0L
        var n = 0
        for (f in dir.walkTopDown()) {
            // v1.17: a walk with no cancellation check runs to completion
            // even after the screen is gone.
            if (++n % 500 == 0) ensureActive()
            if (f.isFile) total += f.length()
        }
        total
    }

    suspend fun largeFiles(dir: File, limit: Int = 20): List<File> = withContext(Dispatchers.IO) {
        val all = mutableListOf<File>()
        var n = 0
        for (f in dir.walkTopDown()) {
            if (++n % 500 == 0) ensureActive()
            if (f.isFile && f.length() > 50L * 1024 * 1024) all.add(f)
        }
        all.sortedByDescending { it.length() }.take(limit)
    }

    suspend fun findDuplicates(dir: File, onProgress: (Int) -> Unit = {}): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val bySize = mutableMapOf<Long, MutableList<File>>()
            var scanned = 0
            // Plain for loop (not forEach): ensureActive() only resolves in
            // the withContext scope, not inside a forEach lambda.
            // v1.17: skip Android/ (app-private data the user shouldn't
            // delete) and hidden dirs — the old scan hashed all of them.
            for (f in dir.walkTopDown()
                .onEnter { d -> d.name != "Android" && !d.name.startsWith(".") }) {
                if (f.isFile && f.length() > 0) {
                    bySize.getOrPut(f.length()) { mutableListOf() }.add(f)
                    scanned++
                    if (scanned % 100 == 0) ensureActive() // lets the UI cancel the scan
                    if (scanned % 500 == 0) onProgress(scanned)
                }
            }
            val groups = mutableListOf<DuplicateGroup>()
            for ((size, files) in bySize) {
                if (files.size < 2) continue
                val byHash = mutableMapOf<String, MutableList<File>>()
                for (f in files) {
                    // v1.17: Stop did nothing during hashing — the loop
                    // never checked cancellation. And a null hash (unreadable
                    // file) used to group every unreadable file together
                    // as "duplicates" of each other; skip those.
                    ensureActive()
                    val h = hashFile(f) ?: continue
                    byHash.getOrPut(h) { mutableListOf() }.add(f)
                }
                for ((_, dups) in byHash) if (dups.size > 1) groups.add(DuplicateGroup(size, dups))
            }
            groups.sortedByDescending { it.size * it.files.size }
        }

    private fun hashFile(f: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { ins ->
                val buf = ByteArray(8192)
                var n: Int
                while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { null }
    }

    suspend fun copyFiles(files: List<File>, destDir: File, move: Boolean, onProgress: (Int, Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                destDir.mkdirs()
                val destCanon = destDir.canonicalPath
                var allOk = true
                files.forEachIndexed { idx, src ->
                    var ok = true
                    try {
                        // v1.15: never copy/move a folder into itself — that
                        // recursed forever and filled the storage with junk.
                        val srcCanon = src.canonicalPath
                        if (destCanon == srcCanon || destCanon.startsWith("$srcCanon${File.separator}")) {
                            // v1.16: skip just this item instead of aborting the
                            // whole batch — the other files are still valid.
                            allOk = false
                            onProgress(idx + 1, files.size)
                            return@forEachIndexed
                        }
                        // v1.16: moving a file into the folder it already lives
                        // in is a no-op — the old code renamed it to
                        // "name (1).ext" and deleted the original.
                        if (move && src.parentFile?.canonicalPath == destCanon) {
                            onProgress(idx + 1, files.size)
                            return@forEachIndexed
                        }
                        // v1.13: never silently overwrite — a same-named file in
                        // the destination gets "name (1).ext" instead of being
                        // destroyed by the paste.
                        val dest = nonCollidingDest(destDir, src.name)
                        if (src.isDirectory) copyDir(src, dest) else copyFile(src, dest)
                        // v1.17: verify the copy before touching the
                        // original — a partial copy + delete is data loss.
                        // And verify the move-delete: a "move" that leaves
                        // the original behind is a copy that lies about it.
                        if (!dest.exists() || (src.isFile && dest.length() != src.length())) {
                            runCatching { deleteRecursively(dest) }
                            ok = false
                        } else if (move) {
                            ok = deleteRecursively(src)
                            if (!ok) runCatching { deleteRecursively(dest) }
                        }
                    } catch (e: Exception) {
                        ok = false
                    }
                    if (!ok) allOk = false
                    onProgress(idx + 1, files.size)
                }
                allOk
            } catch (e: Exception) { false }
        }

    /** A destination name that does not overwrite an existing file.
     *  v1.17: a leading-dot file (".gitignore") used to collide into
     *  " (1).gitignore" — the dot at index 0 is not an extension split. */
    fun nonCollidingDest(dir: File, name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest
        val dot = name.lastIndexOf('.')
        val base = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, "$base ($n)$ext")
            n++
        }
        return dest
    }

    private fun copyFile(src: File, dest: File) {
        FileInputStream(src).use { ins -> FileOutputStream(dest).use { out -> ins.copyTo(out) } }
    }

    private fun copyDir(src: File, dest: File) {
        dest.mkdirs()
        src.listFiles()?.forEach { child ->
            val d = File(dest, child.name)
            if (child.isDirectory) copyDir(child, d) else copyFile(child, d)
        }
    }

    // v1.17: returns whether the file is really gone — every caller used
    // to ignore the result and report success on failure.
    fun deleteRecursively(f: File): Boolean {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        return !f.exists() || f.delete()
    }

    // ---- v1.7: Trash remembers where each file came from ----
    // v1.0–v1.6 restored everything to the internal root, losing the folder.
    // A small index (trash file name -> original parent path) fixes that.
    private fun trashIndex(context: Context) =
        context.getSharedPreferences("nova_files_trash", Context.MODE_PRIVATE)

    fun deleteFromTrash(context: Context, file: File): Boolean {
        // v1.17: only forget the index entry when the file is really gone.
        val ok = deleteRecursively(file)
        if (ok) trashIndex(context).edit().remove(file.name).apply()
        return ok
    }

    suspend fun moveToTrash(context: Context, files: List<File>): Boolean = withContext(Dispatchers.IO) {
        try {
            val trash = trashDir(context)
            val stamp = System.currentTimeMillis()
            val index = trashIndex(context).edit()
            var allOk = true
            files.forEachIndexed { idx, src ->
                // v1.15: the stamp alone collides when two same-named files
                // are trashed together — the index makes each dest unique.
                val dest = File(trash, "${stamp}_${idx}_${src.name}")
                var ok = false
                try {
                    if (src.isDirectory) copyDir(src, dest) else copyFile(src, dest)
                    // v1.17: verify the copy before deleting the original,
                    // and verify the delete — the old code indexed the file
                    // and reported success even when the original was still
                    // sitting in place (copy ok, delete failed = duplicates).
                    ok = dest.exists() && (!src.isFile || dest.length() == src.length())
                    if (ok) ok = deleteRecursively(src)
                } catch (e: Exception) { ok = false }
                if (ok) {
                    index.putString(dest.name, src.parentFile?.absolutePath ?: "")
                } else {
                    allOk = false
                    runCatching { deleteRecursively(dest) }
                    ErrorLog.log(context, "Trash", "Failed to move ${src.name} to Trash")
                }
            }
            index.apply()
            allOk
        } catch (e: Exception) { false }
    }

    suspend fun listTrash(context: Context): List<File> = withContext(Dispatchers.IO) {
        trashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** The original name of a trashed file, for display: strip the numeric
     *  "<stamp>_<index>_" (v1.15) or "<stamp>_" (v1.14) prefix.
     *  v1.17: the stamp is a 13-digit millisecond timestamp — requiring all
     *  13 digits means a user's own file called "123_report.pdf" no longer
     *  displays (and restores) as "report.pdf". */
    fun trashDisplayName(trashName: String): String =
        Regex("^\\d{13}_\\d+_(.*)$").find(trashName)?.groupValues?.get(1)
            ?: Regex("^\\d{13}_(.*)$").find(trashName)?.groupValues?.get(1)
            ?: trashName

    suspend fun restoreFromTrash(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Trash file names are "<stamp>_<index>_<original name>" (v1.15)
            // or "<stamp>_<original name>" (v1.14 and older) — strip only
            // the numeric prefixes so an original name with underscores
            // (e.g. "my_file.jpg") survives intact.
            val name = trashDisplayName(file.name)
            val parentPath = trashIndex(context).getString(file.name, null)
            val parent = parentPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            // v1.15: restore never overwrites — a same-named file at the
            // original location gets "name (1).ext" instead of being destroyed.
            val dest = if (parent != null && parent.isDirectory) {
                nonCollidingDest(parent, name)
            } else {
                nonCollidingDest(internalRoot(), name)
            }
            if (file.isDirectory) copyDir(file, dest) else copyFile(file, dest)
            // v1.17: verify the copy before removing the trash entry, and
            // verify the trash removal — a failed delete used to leave the
            // file in both places while reporting success.
            val copied = dest.exists() && (!file.isFile || dest.length() == file.length())
            if (!copied) {
                runCatching { deleteRecursively(dest) }
                return@withContext false
            }
            if (!deleteFromTrash(context, file)) {
                runCatching { deleteRecursively(dest) }
                return@withContext false
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun emptyTrash(context: Context) = withContext(Dispatchers.IO) {
        trashDir(context).listFiles()?.forEach { deleteRecursively(it) }
        trashIndex(context).edit().clear().apply()
    }

    // The Trash screen promises "30 days" — actually enforce it, or the claim is a lie.
    suspend fun purgeOldTrash(context: Context, maxAgeDays: Int = 30) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000
        trashDir(context).listFiles()?.forEach {
            if (it.lastModified() < cutoff) deleteFromTrash(context, it)
        }
    }

    /** Compress files to a ZIP. Returns the created file, or null on failure.
     *  v1.16: the output never overwrites an existing ZIP — a same-named
     *  one gets "name (1).zip" (the old code truncated it silently). */
    suspend fun zipFiles(files: List<File>, zipFile: File): File? = withContext(Dispatchers.IO) {
        try {
            val dest = nonCollidingDest(zipFile.parentFile ?: File("."), zipFile.name)
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                for (f in files) addToZip(f, "", zos)
            }
            dest
        } catch (e: Exception) { null }
    }

    private fun addToZip(f: File, base: String, zos: ZipOutputStream) {
        val entryName = if (base.isEmpty()) f.name else "$base/${f.name}"
        if (f.isDirectory) {
            // v1.17: write the directory entry itself, or empty folders
            // vanish from the ZIP entirely.
            zos.putNextEntry(ZipEntry("$entryName/"))
            zos.closeEntry()
            f.listFiles()?.forEach { addToZip(it, entryName, zos) }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(f).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    // ZIP-slip guard: a crafted archive entry like "../../evil" must never
    // escape the destination folder. v1.0–v1.6 wrote entries unchecked.
    suspend fun unzip(zipFile: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            val destCanon = destDir.canonicalPath
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith("$destCanon${File.separator}")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) { false }
    }

    // Share files with other apps (moved here from BrowserScreen so the
    // viewer and the analyzer can use it too).
    // v1.17: the old version silently did nothing on many phones. Three
    // real defects: (1) it used ACTION_SEND_MULTIPLE even for ONE file, so
    // the share sheet filtered out apps that only handle ACTION_SEND;
    // (2) it never set ClipData, so the URI-permission grant often never
    // reached the app the user picked — the chooser opened, the file never
    // attached; (3) every failure was swallowed by an empty catch, so the
    // user saw nothing and the Error log recorded nothing. Returns false
    // when nothing could be shared, so callers can say so.
    fun shareFiles(context: Context, files: List<File>): Boolean {
        val shareable = files.filter { it.isFile && it.exists() }
        if (shareable.isEmpty()) return false
        return try {
            val uris = shareable.map { FileProvider.getUriForFile(context, "com.nova.files.provider", it) }
            val mime = if (shareable.size == 1) {
                android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(shareable[0].extensionLower()) ?: "*/*"
            } else "*/*"
            val intent = Intent(
                if (shareable.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = mime
                if (shareable.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                // ClipData is how the URI-permission grant reaches the
                // target app for EXTRA_STREAM URIs — the flag alone is
                // not enough on several OEMs.
                clipData = android.content.ClipData.newUri(
                    context.contentResolver, "Shared files", uris[0]
                ).apply {
                    for (i in 1 until uris.size) addItem(
                        android.content.ClipData.Item(uris[i])
                    )
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share"))
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Share", "Failed to share ${shareable.size} file(s)", e)
            false
        }
    }

    // ---- v1.7: extract ZIP, 7z, RAR, TAR, TAR.GZ ----
    // v1.0–v1.6 only extracted ZIP, while the menu offered "Extract" for
    // RAR/7z too and silently failed. 7z via Apache Commons Compress,
    // RAR via junrar, TAR/TAR.GZ via Commons Compress.
    suspend fun extractArchive(archive: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // v1.17: junrar cannot read RAR5 at all — refuse fast instead
            // of failing inside the extractor with a generic error.
            if (archive.extensionLower() == "rar" && isRar5(archive)) return@withContext false
            when (archive.extensionLower()) {
                "zip" -> unzip(archive, destDir)
                "7z" -> extract7z(archive, destDir)
                "rar" -> extractRar(archive, destDir)
                "tar" -> extractTar(archive, destDir, gzipped = false)
                // v1.16: a plain .gz is a single compressed file, NOT a tar
                // — feeding it to extractTar read 0 entries and reported
                // success with an empty destination. Only .tar.gz/.tgz are
                // gzipped tars; a plain .gz is gunzipped to one file.
                "tgz" -> extractTar(archive, destDir, gzipped = true)
                "gz" -> if (archive.nameWithoutExtension.endsWith(".tar", ignoreCase = true)) {
                    extractTar(archive, destDir, gzipped = true)
                } else {
                    extractGz(archive, destDir)
                }
                else -> false
            }
        } catch (e: Exception) { false }
    }

    /** Gunzip a plain .gz to a single file (the name minus the .gz). */
    private fun extractGz(archive: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            val outName = archive.name.removeSuffix(".gz").removeSuffix(".GZ")
                .takeIf { it.isNotBlank() } ?: "file"
            val out = File(destDir, outName)
            java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
                out.outputStream().buffered().use { o -> gz.copyTo(o) }
            }
            out.exists() && out.length() > 0
        } catch (e: Exception) { false }
    }

    // Same zip-slip guard as unzip(): entries must stay inside destDir.
    private fun safeOut(destDir: File, name: String): File? {
        val out = File(destDir, name)
        val base = destDir.canonicalPath
        val canon = out.canonicalPath
        return if (canon == base || canon.startsWith("$base${File.separator}")) out else null
    }

    private fun extract7z(archive: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            org.apache.commons.compress.archivers.sevenz.SevenZFile(archive).use { sz ->
                var entry = sz.nextEntry
                while (entry != null) {
                    val out = safeOut(destDir, entry.name)
                    if (out != null) {
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            sz.getInputStream(entry)?.use { ins ->
                                FileOutputStream(out).use { o -> ins.copyTo(o) }
                            }
                        }
                    }
                    entry = sz.nextEntry
                }
            }
            true
        } catch (e: Exception) { false }
    }

    // v1.13: the old code delegated to Junrar.extract(), which writes
    // entries unchecked — a crafted RAR could escape the destination
    // (path traversal). Now entries go through the same safeOut guard
    // as ZIP/7z/TAR, one file at a time.
    private fun extractRar(archive: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            com.github.junrar.Archive(archive).use { arch ->
                var header = arch.nextFileHeader()
                while (header != null) {
                    val out = safeOut(destDir, header.fileNameString)
                    if (out != null) {
                        if (header.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { o -> arch.extractFile(header, o) }
                        }
                    }
                    header = arch.nextFileHeader()
                }
            }
            true
        } catch (e: Exception) { false }
    }

    private fun extractTar(archive: File, destDir: File, gzipped: Boolean): Boolean {
        return try {
            destDir.mkdirs()
            val raw: java.io.InputStream = if (gzipped) {
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(FileInputStream(archive))
            } else FileInputStream(archive)
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(raw).use { tis ->
                var entry = tis.nextEntry
                while (entry != null) {
                    val out = safeOut(destDir, entry.name)
                    if (out != null) {
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tis.copyTo(it) }
                        }
                    }
                    entry = tis.nextEntry
                }
            }
            true
        } catch (e: Exception) { false }
    }

    // ---- v1.7: UI preferences (sort / view / hidden / theme survive restarts) ----
    data class UiPrefs(
        val sort: SortMode = SortMode.NAME,
        val grid: Boolean = false,
        val showHidden: Boolean = false,
        val darkTheme: Boolean = true
    )

    fun loadUiPrefs(context: Context): UiPrefs {
        val p = context.getSharedPreferences("nova_files", Context.MODE_PRIVATE)
        return UiPrefs(
            sort = runCatching { SortMode.valueOf(p.getString("ui_sort", "NAME") ?: "NAME") }.getOrDefault(SortMode.NAME),
            grid = p.getBoolean("ui_grid", false),
            showHidden = p.getBoolean("ui_show_hidden", false),
            darkTheme = p.getBoolean("ui_dark_theme", true)
        )
    }

    fun updateUiPrefs(context: Context, transform: (UiPrefs) -> UiPrefs) {
        val prefs = transform(loadUiPrefs(context))
        context.getSharedPreferences("nova_files", Context.MODE_PRIVATE).edit()
            .putString("ui_sort", prefs.sort.name)
            .putBoolean("ui_grid", prefs.grid)
            .putBoolean("ui_show_hidden", prefs.showHidden)
            .putBoolean("ui_dark_theme", prefs.darkTheme)
            .apply()
    }

    fun vaultPinHash(context: Context): String? =
        context.getSharedPreferences("nova_files", Context.MODE_PRIVATE).getString("vault_pin", null)

    // v1.17: the vault PIN is now salted. v1 stored a bare unsalted
    // SHA-256 hex — anyone who reads the backup (or the prefs file) can
    // crack a 4–6 digit PIN offline in seconds by hashing 0000–999999.
    // Format: "v2:<salt hex>:<SHA-256(salt + pin) hex>". An old v1 hash is
    // still accepted once, then migrated to the salted format on the spot.
    fun setVaultPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val stored = "v2:${salt.toHex()}:${pinHash(pin, salt)}"
        context.getSharedPreferences("nova_files", Context.MODE_PRIVATE)
            .edit().putString("vault_pin", stored).apply()
    }

    fun checkVaultPin(context: Context, pin: String): Boolean {
        val saved = vaultPinHash(context) ?: return false
        return if (saved.startsWith("v2:")) {
            val parts = saved.split(":")
            if (parts.size != 3) return false
            val salt = parts[1].hexToBytes() ?: return false
            val expected = parts[2].hexToBytes() ?: return false
            val actual = pinHash(pin, salt).hexToBytes() ?: return false
            MessageDigest.isEqual(expected, actual)
        } else {
            val hash = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).toHex()
            val ok = MessageDigest.isEqual(
                saved.lowercase().toByteArray(), hash.lowercase().toByteArray()
            )
            // Migrate the old unsalted hash away on a successful unlock.
            if (ok) setVaultPin(context, pin)
            ok
        }
    }

    private fun pinHash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray())
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? = runCatching {
        require(length % 2 == 0)
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    // v1.10: remove the vault PIN entirely. The next Vault entry then shows
    // the setup screen ("Set a vault PIN") — there is never an auto PIN.
    // Files already in the vault are untouched; only the lock is removed.
    fun clearVaultPin(context: Context) {
        context.getSharedPreferences("nova_files", Context.MODE_PRIVATE)
            .edit().remove("vault_pin").apply()
    }
}

/**
 * In-app error log (v1.8). The user reported crashes with no visible error
 * anywhere, so every failure is written here and shown on the Error log
 * screen (Home → Error log), where it can be copied and shared.
 * Entries are separated by a "---" line and capped at 100; the log itself
 * must never crash the app, so every operation is guarded.
 */
object ErrorLog {
    private const val FILE_NAME = "error-log.txt"
    private const val MAX_ENTRIES = 100
    private const val SEPARATOR = "\n---\n"

    // v1.17: read-modify-write with no lock — two threads logging at once
    // interleaved and silently lost entries.
    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun log(context: Context, tag: String, message: String, e: Throwable? = null) {
        synchronized(lock) {
            try {
                val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
                val detail = e?.let {
                    "\n${it.javaClass.simpleName}: ${it.message}\n" +
                        it.stackTraceToString().lines().take(12).joinToString("\n")
                } ?: ""
                val entry = "[$time] $tag: $message$detail"
                val f = file(context)
                val existing = if (f.exists()) f.readText() else ""
                val all = (if (existing.isBlank()) emptyList() else existing.split(SEPARATOR)) + entry
                f.writeText(all.takeLast(MAX_ENTRIES).joinToString(SEPARATOR))
            } catch (_: Exception) { /* the log itself must never crash the app */ }
        }
    }

    /** Newest first. */
    fun read(context: Context): List<String> = synchronized(lock) {
        try {
            val f = file(context)
            if (!f.exists()) emptyList()
            else f.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.asReversed()
        } catch (_: Exception) { emptyList() }
    }

    fun count(context: Context): Int = synchronized(lock) {
        try {
            val f = file(context)
            if (!f.exists()) 0 else f.readText().split(SEPARATOR).count { it.isNotBlank() }
        } catch (_: Exception) { 0 }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try { file(context).delete() } catch (_: Exception) { }
        }
    }
}
