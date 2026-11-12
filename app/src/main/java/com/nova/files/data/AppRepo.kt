package com.nova.files.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkBytes: Long,
    val installTime: Long,
    val apkPath: String,
    /** Extra APK splits (config.arm64_v8a, config.en, …) — most Play apps
     *  on Android 14 are split APKs, so a base-only backup won't install. */
    val splitPaths: List<String> = emptyList()
)

object AppRepo {

    /** Installed apps, user apps first, then system apps, each by name. */
    fun listApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val out = ArrayList<AppInfo>()
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            for (pkg in packages) {
                val ai = pkg.applicationInfo ?: continue
                val apk = File(ai.sourceDir)
                // v1.15: count the splits too — the shown size understated
                // real app size, and a base-only backup won't install.
                val splits = ai.splitSourceDirs?.toList().orEmpty()
                val allApks = listOf(ai.sourceDir) + splits
                val totalBytes = allApks.sumOf { File(it).let { f -> if (f.exists()) f.length() else 0L } }
                out += AppInfo(
                    label = pm.getApplicationLabel(ai).toString(),
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "",
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong(),
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    apkBytes = totalBytes,
                    installTime = pkg.firstInstallTime,
                    apkPath = ai.sourceDir,
                    splitPaths = splits
                )
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Files", "Failed to list apps", e)
            return emptyList()
        }
        return out.sortedWith(
            compareByDescending<AppInfo> { !it.isSystem }.thenBy { it.label.lowercase() }
        )
    }

    /**
     * Copy the app's APK(s) into Downloads/NOVA/Apps/<label>-<version>/.
     * v1.15: backs up the base APK AND every split (config.arm64_v8a,
     * config.en, …) — a base-only backup of a split APK fails to install.
     * Returns the base APK file.
     */
    fun backupApk(context: Context, app: AppInfo): File? {
        return try {
            val src = File(app.apkPath)
            if (!src.exists()) return null
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "NOVA/Apps"
            )
            if (!dir.exists()) dir.mkdirs()
            val safeLabel = app.label.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val appDir = File(dir, "$safeLabel-${app.versionName.ifBlank { app.versionCode.toString() }}")
            if (!appDir.exists()) appDir.mkdirs()
            val baseDest = File(appDir, "base.apk")
            src.copyTo(baseDest, overwrite = true)
            app.splitPaths.forEach { splitPath ->
                val split = File(splitPath)
                if (split.exists()) {
                    split.copyTo(File(appDir, split.name), overwrite = true)
                }
            }
            baseDest
        } catch (e: Exception) {
            ErrorLog.log(context, "Files", "Failed to back up ${app.packageName}", e)
            null
        }
    }

    fun uninstallIntent(app: AppInfo): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))

    fun launchIntent(context: Context, app: AppInfo): Intent? =
        context.packageManager.getLaunchIntentForPackage(app.packageName)
}
