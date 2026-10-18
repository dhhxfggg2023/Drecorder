package dev.drecorder.data

import org.json.JSONObject

/**
 * Parses the update manifest.
 *
 * Hand-written against [JSONObject] instead of a serialization library: the schema is
 * eleven fields, and every one of them needs a sane default rather than an exception,
 * because a malformed manifest must produce "no update" and never a crash.
 */
fun parseUpdateManifest(json: String): UpdateManifest? = runCatching {
    val root = JSONObject(json)
    val mirrors = root.optJSONArray("mirrors")?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()

    UpdateManifest(
        versionCode = root.optLong("versionCode", 0L),
        versionName = root.optString("versionName", ""),
        minSupportedVersionCode = root.optLong("minSupportedVersionCode", 0L),
        apkUrl = root.optString("apkUrl", ""),
        mirrors = mirrors,
        sizeBytes = root.optLong("sizeBytes", 0L),
        sha256 = root.optString("sha256", ""),
        signingCertSha256 = root.optString("signingCertSha256", ""),
        publishedAt = root.optString("publishedAt", ""),
        releaseNotes = root.optString("releaseNotes", ""),
        force = root.optBoolean("force", false),
    )
}.getOrNull()

/** APK download candidates in order: primary url first, then any mirrors. */
fun UpdateManifest.downloadCandidates(): List<String> =
    (listOf(apkUrl) + mirrors).filter { it.isNotBlank() }.distinct()

/**
 * The url the app should try first, and a human-readable note about whether the
 * manifest expects this build to be updated at all.
 */
fun UpdateManifest.requiresUpdate(currentVersionCode: Long): Boolean =
    force || currentVersionCode < minSupportedVersionCode
