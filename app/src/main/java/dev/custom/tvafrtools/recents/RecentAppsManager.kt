package dev.custom.tvafrtools.recents

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dev.custom.tvafrtools.server.ServerClient
import android.util.Log
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Universal Recent Apps Manager for Android TV.
 *
 * Implements the proven tvQuickActions mechanism (AbstractC0281c.f4637k + W1.H + W1.J):
 * 1. Live session tracking from TvAfrAccessibilityService (TYPE_WINDOW_STATE_CHANGED)
 * 2. Historical query from UsageStatsManager (last 48 hours)
 * 3. Privileged / ADB recents fallback (dumpsys activity recents / ActivityTaskManager)
 * 4. Resolves app labels and TV banners / icons for native D-Pad switching & closing.
 */
object RecentAppsManager {

    private val EXCLUDED_PACKAGES = hashSetOf(
        "com.spocky.projengmenu",
        "com.nvidia.osc",
        "com.android.systemui",
        "com.google.android.packageinstaller",
        "com.google.android.katniss",
        "com.google.android.tvrecommendations",
        "com.google.android.gms",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "ca.dstudio.atvlauncher.free",
        "ca.dstudio.atvlauncher.pro",
        "com.wolf.firelauncher",
        "com.nes.recents",
        "android",
        "dev.custom.tvafrtools"
    )

    private val liveRecentPackages = CopyOnWriteArrayList<String>()

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val isForeground: Boolean
    )

    fun isExcluded(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return true
        if (EXCLUDED_PACKAGES.contains(packageName)) return true
        if (packageName.startsWith("com.android.systemui") || packageName.startsWith("android")) return true

        // Filter default home / launcher activity
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo?.activityInfo?.packageName == packageName) return true

        return false
    }

    /**
     * Called whenever TvAfrAccessibilityService detects TYPE_WINDOW_STATE_CHANGED
     * (matches AbstractC0281c.e in tvQuickActions).
     */
    fun onAppForegrounded(context: Context, packageName: String) {
        if (isExcluded(context, packageName)) return

        liveRecentPackages.remove(packageName)
        liveRecentPackages.add(0, packageName)
        while (liveRecentPackages.size > 30) {
            liveRecentPackages.removeAt(liveRecentPackages.lastIndex)
        }
        Log.d("RecentAppsManager", "onAppForegrounded -> $packageName (count: ${liveRecentPackages.size})")
    }

    /**
     * Pre-loads historical recent apps from UsageStatsManager or PrivilegedServer/dumpsys
     * on service start or cold start.
     */
    fun loadInitialRecents(context: Context) {
        Thread {
            try {
                // 1. Query UsageStatsManager
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val calendar = Calendar.getInstance()
                    val end = calendar.timeInMillis
                    calendar.add(Calendar.DAY_OF_YEAR, -2) // last 48 hours
                    val start = calendar.timeInMillis
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    if (!stats.isNullOrEmpty()) {
                        val sorted = stats.filter { it.lastTimeUsed > 0 && !isExcluded(context, it.packageName) }
                            .sortedByDescending { it.lastTimeUsed }
                            .map { it.packageName }
                            .distinct()

                        for (pkg in sorted.reversed()) {
                            if (!liveRecentPackages.contains(pkg)) {
                                liveRecentPackages.add(0, pkg)
                            }
                        }
                    }
                }

                // 2. Query PrivilegedServer / dumpsys recents
                val serverRecents = ServerClient.getRecentPackages()
                for (pkg in serverRecents.reversed()) {
                    if (!isExcluded(context, pkg) && !liveRecentPackages.contains(pkg)) {
                        liveRecentPackages.add(0, pkg)
                    }
                }
                Log.d("RecentAppsManager", "RecentAppsManager initialized: ${liveRecentPackages.size} apps in history")
            } catch (e: Exception) {
                Log.w("RecentAppsManager", "Error loading initial recents")
            }
        }.start()
    }

    /**
     * Resolves display metadata (label, TV banner / icon) for recent packages
     * (matches W1.H in tvQuickActions).
     */
    fun getRecentApps(context: Context, currentFgPkg: String): List<AppInfo> {
        val pm = context.packageManager
        val result = mutableListOf<AppInfo>()
        val packageCandidates = LinkedHashSet<String>()

        // 1. Add current foreground app if valid
        if (currentFgPkg.isNotBlank() && !isExcluded(context, currentFgPkg)) {
            packageCandidates.add(currentFgPkg)
        }

        // 2. Add live recorded packages
        packageCandidates.addAll(liveRecentPackages.filter { !isExcluded(context, it) })

        // 3. Fallback: UsageStatsManager if list is still small
        if (packageCandidates.size < 6) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val calendar = Calendar.getInstance()
                    val end = calendar.timeInMillis
                    calendar.add(Calendar.DAY_OF_YEAR, -2)
                    val start = calendar.timeInMillis
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    if (!stats.isNullOrEmpty()) {
                        val sorted = stats.filter { it.lastTimeUsed > 0 && !isExcluded(context, it.packageName) }
                            .sortedByDescending { it.lastTimeUsed }
                            .map { it.packageName }
                        packageCandidates.addAll(sorted)
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Fallback: PrivilegedServer / dumpsys recents
        if (packageCandidates.size < 6) {
            try {
                val serverRecents = ServerClient.getRecentPackages()
                packageCandidates.addAll(serverRecents.filter { !isExcluded(context, it) })
            } catch (_: Exception) {}
        }

        // Resolve info for each candidate
        for (pkg in packageCandidates) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(ai).toString()
                var icon: Drawable? = pm.getApplicationBanner(ai)
                if (icon == null) {
                    val launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg)
                        ?: pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        try {
                            icon = pm.getActivityBanner(launchIntent)
                        } catch (_: Exception) {}
                    }
                }
                if (icon == null) {
                    icon = pm.getApplicationLogo(ai)
                }
                if (icon == null) {
                    icon = pm.getApplicationIcon(ai)
                }

                result.add(
                    AppInfo(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        isForeground = (pkg == currentFgPkg)
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed or not an application, skip
            }
        }

        return result
    }
}
