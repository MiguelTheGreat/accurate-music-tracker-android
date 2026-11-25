package com.musictracker.app.tracking

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.musictracker.app.data.preferences.DefaultAllowlist

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isDefaultMusicApp: Boolean
)

interface SystemPermissionHelper {
    fun isNotificationAccessGranted(): Boolean
    fun openNotificationListenerSettings(context: Context): Boolean
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestBatteryOptimizationExemption(context: Context): Boolean
    fun openBatteryOptimizationSettings(context: Context): Boolean
    fun getInstalledLaunchableApps(): List<InstalledAppInfo>
}

class AndroidSystemPermissionHelper(
    context: Context,
    private val defaultMusicPackages: Set<String> = DefaultAllowlist.packages
) : SystemPermissionHelper {

    private val appContext: Context = context.applicationContext ?: context

    override fun isNotificationAccessGranted(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val notificationManager = appContext.getSystemService(NotificationManager::class.java)
                ?: return@runCatching false
            val componentName = ComponentName(appContext, MusicNotificationListenerService::class.java)
            notificationManager.isNotificationListenerAccessGranted(componentName)
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(appContext)
                .contains(appContext.packageName)
        }
    }.getOrDefault(false)

    override fun openNotificationListenerSettings(context: Context): Boolean {
        val primary = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val fallback = Intent(Settings.ACTION_SETTINGS)
        return safeStartActivity(context, primary, fallback)
    }

    override fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
            ?: return@runCatching false
        powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }.getOrDefault(false)

    @SuppressLint("BatteryLife")
    override fun requestBatteryOptimizationExemption(context: Context): Boolean {
        val primary = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return safeStartActivity(context, primary, fallback)
    }

    override fun openBatteryOptimizationSettings(context: Context): Boolean {
        val primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val fallback = Intent(Settings.ACTION_SETTINGS)
        return safeStartActivity(context, primary, fallback)
    }

    override fun getInstalledLaunchableApps(): List<InstalledAppInfo> = runCatching {
        val pm = appContext.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(queryIntent, 0)
        }

        val selfPackage = appContext.packageName
        val seenPackages = LinkedHashMap<String, InstalledAppInfo>()

        for (resolveInfo in resolveInfos) {
            val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || pkg == selfPackage || seenPackages.containsKey(pkg)) {
                continue
            }
            val label = runCatching {
                resolveInfo.loadLabel(pm)?.toString()?.trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: pkg

            seenPackages[pkg] = InstalledAppInfo(
                packageName = pkg,
                appName = label,
                isDefaultMusicApp = pkg in defaultMusicPackages
            )
        }

        seenPackages.values.sortedWith(
            compareByDescending<InstalledAppInfo> { it.isDefaultMusicApp }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
                .thenBy { it.packageName }
        )
    }.getOrDefault(emptyList())

    private fun safeStartActivity(
        context: Context,
        primary: Intent,
        fallback: Intent? = null
    ): Boolean {
        fun prepare(intent: Intent): Intent = intent.apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            context.startActivity(prepare(primary))
            true
        } catch (_: ActivityNotFoundException) {
            tryFallback(context, fallback, ::prepare)
        } catch (_: SecurityException) {
            tryFallback(context, fallback, ::prepare)
        }
    }

    private fun tryFallback(
        context: Context,
        fallback: Intent?,
        prepare: (Intent) -> Intent
    ): Boolean {
        if (fallback == null) return false
        return try {
            context.startActivity(prepare(fallback))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
