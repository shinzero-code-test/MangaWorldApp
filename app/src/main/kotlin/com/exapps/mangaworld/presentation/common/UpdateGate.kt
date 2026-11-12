package com.exapps.mangaworld.presentation.common

/**
 * In-app update gate — pure decision logic (v9.8.0).
 *
 * The app ships through **GitHub Releases**, not Google Play, so there is no
 * store to pull an update from and no store to nag in. The only channel a
 * user has to learn that a fixed build exists is the app itself, driven by
 * Remote Config:
 *
 *  - `min_supported_version` — the oldest build that still works. Below it the
 *    app is blocked (the fleet on 9.6.x cannot load covers/chapter images).
 *  - `latest_version` — the newest published build. Below it we nag once.
 *  - `update_url` — where the download lives.
 *
 * Deliberately free of Android / Compose / BuildConfig references so the
 * whole decision table is unit-testable on the JVM (see `UpdateGateTest`).
 *
 * Fail-open rule: an unusable threshold NEVER blocks. A typo in Remote
 * Config must not brick the app for the whole fleet — the worst outcome of a
 * bad value is "no prompt", not "no app".
 */
object UpdateGate {

    /** What the gate decided for the installed build. */
    enum class Decision {
        /** Installed build is current (or the thresholds are unusable). */
        Current,

        /**
         * Installed build is below `min_supported_version` → **blocking**
         * prompt: the app cannot be used until it is updated.
         */
        Required,

        /**
         * Installed build is at/above the minimum but below `latest_version`
         * → soft, one-shot notice.
         */
        Available
    }

    /**
     * Numeric, dotted-version compare (`"9.7.2"` vs `"9.7.10"`).
     *
     * Segments are compared **as numbers**, so `9.7.10` is newer than `9.7.2`
     * (a lexicographic compare gets that backwards — the reason this helper
     * exists instead of `String.compareTo`).
     *
     * @return negative / zero / positive like [Comparable.compareTo], or
     *   **null** when either side cannot be parsed — "unknown", and an
     *   unknown version must never be treated as "older".
     */
    fun compareVersions(a: String, b: String): Int? {
        val left = parseVersion(a) ?: return null
        val right = parseVersion(b) ?: return null
        return compareSegments(left, right)
    }

    /**
     * Parse a dotted numeric version into its segments.
     *
     * `"9.7.2"` → `[9, 7, 2]`. Missing segments compare as `0`, so `"9.7"` and
     * `"9.7.0"` are the same version.
     *
     * A leading `v` (dashboard release tags use `v9.7.2`) and a trailing
     * pre-release / build suffix (`-rc1`, `+build.7`) are ignored: ordering
     * never depends on them.
     *
     * @return the segments, or **null** when the string is not a plain
     *   dotted numeric version (blank, a non-numeric segment, a negative or
     *   overflowing number).
     */
    fun parseVersion(raw: String): List<Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Drop a "v" prefix ("v9.7.2") and any pre-release / build suffix.
        val body = trimmed
            .let { if (it.length > 1 && (it[0] == 'v' || it[0] == 'V')) it.substring(1) else it }
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (body.isEmpty()) return null
        val parts = body.split('.')
        if (parts.isEmpty()) return null
        val segments = ArrayList<Int>(parts.size)
        for (part in parts) {
            if (part.isEmpty()) return null
            val value = part.toIntOrNull() ?: return null
            if (value < 0) return null
            segments.add(value)
        }
        return segments
    }

    /**
     * The gate decision for the installed build.
     *
     * The minimum is checked FIRST and wins: a build below the minimum is
     * blocked regardless of what "latest" says, so a pair of inconsistent
     * published values (`min` above `latest`) can never downgrade a block
     * into a mere notice.
     *
     * @param installedVersion the running build (e.g. `"9.7.2"`)
     * @param minSupportedVersion the oldest still-supported build; blank means
     *   "no minimum" and therefore never blocks
     * @param latestVersion the newest published build; blank means "nothing
     *   newer to offer"
     */
    fun evaluate(
        installedVersion: String,
        minSupportedVersion: String,
        latestVersion: String
    ): Decision {
        // An unparseable installed version is "unknown", not "old" — never
        // block on it.
        val installed = parseVersion(installedVersion) ?: return Decision.Current

        val minimum = parseVersion(minSupportedVersion)
        if (minimum != null && compareSegments(installed, minimum) < 0) {
            return Decision.Required
        }

        val latest = parseVersion(latestVersion)
        if (latest != null && compareSegments(installed, latest) < 0) {
            return Decision.Available
        }

        return Decision.Current
    }

    /**
     * Whether a Remote Config `update_url` may be handed to the browser.
     *
     * The URL is remote input: only `https://` is accepted, so a broken or
     * hostile value can never turn the download button into an `intent://` /
     * `javascript:` launch. Anything else falls back to the shipped default.
     */
    fun isUsableUpdateUrl(url: String): Boolean = url.trim().startsWith("https://")

    /** Segment-wise numeric compare; missing segments count as `0`. */
    private fun compareSegments(left: List<Int>, right: List<Int>): Int {
        val width = maxOf(left.size, right.size)
        for (i in 0 until width) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
