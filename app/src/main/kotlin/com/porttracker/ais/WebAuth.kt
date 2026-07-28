package com.porttracker.ais

/**
 * Defaults for the admin web interface's login.
 *
 * The interface used to be open to anything on the same network: web auth was
 * off by default, so a station on a shared wifi answered its full settings and
 * log endpoints - which include credentials - to whoever asked first. It is now
 * gated out of the box with these credentials.
 *
 * These are a known default, not a secret. They stop a passer-by, not anyone
 * who has read this file. Two things keep that from being the whole story:
 * the phone's own UI comes in over loopback and is never challenged, so the
 * operator is not locked out; and exposing a station beyond the LAN through the
 * FRP tunnel still forces a generated password, because
 * [AisReceiverService.ensureRemoteAuthConfigured] treats these defaults as
 * "not configured yet".
 *
 * A per-install random password would be the right answer for a consumer
 * rollout, where a shared default is only marginally better than none.
 */
object WebAuth {
    const val USERNAME = "admin"
    const val PASSWORD = "admin"
    const val ENABLED_BY_DEFAULT = true

    /**
     * True if these are still the shipped credentials rather than something the
     * operator chose. Used to decide whether the station may be published over
     * the remote tunnel as-is.
     */
    fun isStillDefault(username: String, password: String): Boolean =
        username.isEmpty() || password.isEmpty() ||
            (username == USERNAME && password == PASSWORD)
}
