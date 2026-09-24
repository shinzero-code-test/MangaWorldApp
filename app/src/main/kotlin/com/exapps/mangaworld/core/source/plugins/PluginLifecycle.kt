package com.exapps.mangaworld.core.source.plugins

/**
 * Plugin lifecycle state machine (§11A).
 *
 * Only [PluginStatus.ENABLED] is eligible for normal source selection — the registry
 * and every UI surface must treat all other states as unavailable, each with its own
 * reason (update prompt, incompatibility notice, quarantine warning, revocation).
 * Pure logic, JVM-safe.
 */
object PluginLifecycle {

    /** Events that move a plugin between states. */
    enum class Event {
        /** Payload verified (signature + schema + smoke). */
        INSTALL_VERIFIED,

        /** Payload failed validation. */
        MARK_INVALID,

        /** User (or policy) enables an installed plugin. */
        ENABLE,

        /** User (or policy) disables it. */
        DISABLE,

        /** Sync learned of a newer compatible signed version. */
        UPDATE_KNOWN,

        /** Manifest valid but requires unsupported app/engine/bridge versions. */
        MARK_INCOMPATIBLE,

        /** Runtime failures tripped the health heuristic. */
        QUARANTINE,

        /** Server-side signed kill-switch. */
        REVOKE,

        /** App upgraded: re-evaluate previously incompatible plugins. */
        APP_UPGRADED,

        /** Quarantined plugin passed a fresh smoke check. */
        REVERIFY_OK
    }

    /**
     * Single-step transition. Unknown combinations hold the current state (fail closed:
     * no event may silently enable a plugin).
     */
    fun transition(status: PluginStatus, event: Event): PluginStatus = when (status) {
        PluginStatus.AVAILABLE -> when (event) {
            Event.INSTALL_VERIFIED -> PluginStatus.INSTALLED
            Event.MARK_INVALID -> PluginStatus.INVALID
            Event.MARK_INCOMPATIBLE -> PluginStatus.INCOMPATIBLE
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.INSTALLED -> when (event) {
            Event.ENABLE -> PluginStatus.ENABLED
            Event.DISABLE -> PluginStatus.DISABLED
            Event.UPDATE_KNOWN -> PluginStatus.UPDATE_AVAILABLE
            Event.MARK_INVALID -> PluginStatus.INVALID
            Event.MARK_INCOMPATIBLE -> PluginStatus.INCOMPATIBLE
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.ENABLED -> when (event) {
            Event.DISABLE -> PluginStatus.DISABLED
            Event.UPDATE_KNOWN -> PluginStatus.UPDATE_AVAILABLE
            Event.QUARANTINE -> PluginStatus.QUARANTINED
            Event.MARK_INVALID -> PluginStatus.INVALID
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.DISABLED -> when (event) {
            Event.ENABLE -> PluginStatus.ENABLED
            Event.UPDATE_KNOWN -> PluginStatus.UPDATE_AVAILABLE
            Event.MARK_INVALID -> PluginStatus.INVALID
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.UPDATE_AVAILABLE -> when (event) {
            // Fresh payload verified for the pending update.
            Event.INSTALL_VERIFIED -> PluginStatus.INSTALLED
            Event.DISABLE -> PluginStatus.DISABLED
            Event.MARK_INVALID -> PluginStatus.INVALID
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.INCOMPATIBLE -> when (event) {
            // Re-evaluate after upgrade instead of staying declined forever.
            Event.APP_UPGRADED -> PluginStatus.AVAILABLE
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.INVALID -> when (event) {
            Event.INSTALL_VERIFIED -> PluginStatus.INSTALLED
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        PluginStatus.QUARANTINED -> when (event) {
            Event.REVERIFY_OK -> PluginStatus.INSTALLED
            Event.DISABLE -> PluginStatus.DISABLED
            Event.REVOKE -> PluginStatus.REVOKED
            else -> status
        }
        // REVOKED is terminal: only a fresh trusted manifest (new lifecycle) re-enters.
        PluginStatus.REVOKED -> status
    }

    /** Gate every selection path (home, search, detail, workers, notifications) must apply. */
    fun canSelectForReading(status: PluginStatus): Boolean = status == PluginStatus.ENABLED
}
