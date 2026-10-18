package dev.drecorder.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the apps the user can record from.
 *
 * Visibility comes from the MAIN/LAUNCHER `<queries>` entry in the manifest, so
 * this never needs `QUERY_ALL_PACKAGES` (docs/DESIGN.md §2.3).
 */
class InstalledAppsRepository(context: Context) {

    private val appContext = context.applicationContext

    suspend fun launchableApps(excludePackages: Set<String> = emptySet()): List<LaunchableApp> =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            val resolved: List<ResolveInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }

            val entries = resolved.map { info ->
                LauncherEntry(
                    packageName = info.activityInfo?.packageName.orEmpty(),
                    uid = info.activityInfo?.applicationInfo?.uid ?: -1,
                    label = info.loadLabel(pm).toString(),
                )
            }

            entries
                .filter { it.uid >= 0 && it.packageName !in excludePackages }
                .toLaunchableApps()
        }

    /**
     * Primary uid per package, as `AudioPlaybackCaptureConfiguration.addMatchingUid` wants.
     *
     * A package that plays audio from an isolated process owns extra uids; those are
     * not returned here because the picker offers one row per package (DESIGN §2.3).
     */
    suspend fun uidsOf(packageNames: Collection<String>): List<Int> =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            packageNames.mapNotNull { packageName ->
                runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrNull()
            }.distinct()
        }
}
