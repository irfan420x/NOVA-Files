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
    val apkPath: String
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
                out += AppInfo(
                    label = pm.getApplicationLabel(ai).toString(),
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "",
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong(),
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    apkBytes = if (apk.exists()) apk.length() else 0L,
                    installTime = pkg.firstInstallTime,
                    apkPath = ai.sourceDir
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

    /** Copy the app's APK into Downloads/NOVA/Apps. Returns the saved file. */
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
            val dest = File(dir, "$safeLabel-${app.versionName.ifBlank { app.versionCode.toString() }}.apk")
            src.copyTo(dest, overwrite = true)
            dest
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
