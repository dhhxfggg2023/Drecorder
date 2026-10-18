package dev.drecorder.data

/** A launchable app the user may pick as an audio source. */
data class LaunchableApp(
    val packageName: String,
    val uid: Int,
    val label: String,
)

/** One row as reported by the package manager, before deduplication. */
data class LauncherEntry(
    val packageName: String,
    val uid: Int,
    val label: String,
)

/**
 * Collapses launcher rows down to one row per package.
 *
 * A package can expose several launcher activities and can play audio from an
 * isolated process with its own uid. `AudioPlaybackCaptureConfiguration.addMatchingUid`
 * takes uids, so picking the *lowest* uid per package (the shared uid every process of
 * that app belongs to) is what keeps a multi-process app audible; the trade-off is
 * that an app whose audio only ever comes from an isolated process still needs that
 * extra uid selected by hand (docs/DESIGN.md §2.3, §8).
 */
fun List<LauncherEntry>.toLaunchableApps(): List<LaunchableApp> =
    filter { it.packageName.isNotBlank() }
        .groupBy { it.packageName }
        .map { (packageName, entries) ->
            val best = entries.minBy { it.uid }
            LaunchableApp(packageName = packageName, uid = best.uid, label = best.label)
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
