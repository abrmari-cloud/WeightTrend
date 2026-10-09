package com.weighttrend.garmin

import android.content.Context

/**
 * Garmin tokens and sync status, in the app's private storage. The password is
 * never seen by the app: it is typed on Garmin's own page.
 */
class GarminStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("garmin", Context.MODE_PRIVATE)

    var tokens: GarminApi.Tokens?
        get() {
            val access = prefs.getString("access", null) ?: return null
            return GarminApi.Tokens(
                accessToken = access,
                refreshToken = prefs.getString("refresh", null),
                clientId = prefs.getString("client_id", null) ?: return null,
                accessExpiresAtMs = prefs.getLong("access_exp", 0L),
            )
        }
        set(t) {
            prefs.edit().apply {
                if (t == null) {
                    remove("access"); remove("refresh"); remove("client_id"); remove("access_exp")
                } else {
                    putString("access", t.accessToken)
                    putString("refresh", t.refreshToken)
                    putString("client_id", t.clientId)
                    putLong("access_exp", t.accessExpiresAtMs)
                }
            }.apply()
        }

    val isConnected: Boolean get() = tokens != null

    var connectedAtMs: Long
        get() = prefs.getLong("connected_at", 0L)
        set(v) = prefs.edit().putLong("connected_at", v).apply()

    /** True when Garmin rejected the saved login and the user must sign in again. */
    var needsLogin: Boolean
        get() = prefs.getBoolean("needs_login", false)
        set(v) = prefs.edit().putBoolean("needs_login", v).apply()

    var lastStatus: String?
        get() = prefs.getString("last_status", null)
        set(v) = prefs.edit().putString("last_status", v).apply()

    var lastSuccessMs: Long
        get() = prefs.getLong("last_success", 0L)
        set(v) = prefs.edit().putLong("last_success", v).apply()

    fun clear() = prefs.edit().clear().apply()
}
