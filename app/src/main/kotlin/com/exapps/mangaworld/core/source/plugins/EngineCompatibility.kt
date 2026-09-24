package com.exapps.mangaworld.core.source.plugins

/**
 * Engine/bridge/app compatibility gate (schema v1).
 *
 * Runs **after** signature verification and **before** full schema validation: an old app
 * faced with a newer manifest must decline cleanly as [CompatibilityResult.Incompatible],
 * not trip over unknown fields as a schema error. Pure logic, JVM-safe.
 */
object EngineCompatibility {

    /**
     * Checks a parsed manifest header against host capabilities.
     *
     * @param engine manifest `engine` value, or null when missing/unparseable
     *   (missing names fail closed here; malformed shapes are a schema concern).
     */
    fun check(
        engine: SourceEngine?,
        engineApi: Int?,
        bridgeApi: Int?,
        minAppVersion: String?,
        host: HostCapabilities
    ): CompatibilityResult {
        if (engine == null) return CompatibilityResult.Incompatible("unknown engine")
        val supportedRange = host.supportedEngines[engine]
            ?: return CompatibilityResult.Incompatible("engine ${engine.serialName} not supported")
        if (engineApi == null || engineApi !in supportedRange) {
            return CompatibilityResult.Incompatible(
                "engine ${engine.serialName} api $engineApi not in $supportedRange"
            )
        }
        if (engine == SourceEngine.SCRIPT) {
            if (bridgeApi == null || bridgeApi < 1 || bridgeApi > host.supportedBridgeApi) {
                return CompatibilityResult.Incompatible("bridge api $bridgeApi not supported")
            }
        }
        if (minAppVersion == null || compareVersions(host.appVersion, minAppVersion) < 0) {
            return CompatibilityResult.Incompatible(
                "requires app $minAppVersion (have ${host.appVersion})"
            )
        }
        return CompatibilityResult.Compatible
    }

    /**
     * Numeric dotted-version compare (`"8.8.0"` vs `"9.0.0"`). Missing segments read as 0;
     * pre-release/build suffixes (`-rc1`, `+build`) are ignored for ordering. Returns
     * negative/zero/positive like [Comparable.compareTo].
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val width = maxOf(pa.size, pb.size)
        for (i in 0 until width) {
            val diff = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
