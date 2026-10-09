package com.weighttrend.data

import android.content.Context
import com.weighttrend.core.UserProfile

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var profile: UserProfile?
        get() {
            val h = prefs.getFloat("height", 0f)
            if (h <= 0f) return null
            return UserProfile(
                isMale = prefs.getBoolean("male", false),
                birthYear = prefs.getInt("birth_year", 1982),
                birthMonth = prefs.getInt("birth_month", 1),
                heightCm = h.toDouble(),
            )
        }
        set(p) {
            prefs.edit().apply {
                if (p == null) remove("height") else {
                    putBoolean("male", p.isMale)
                    putInt("birth_year", p.birthYear)
                    putInt("birth_month", p.birthMonth)
                    putFloat("height", p.heightCm.toFloat())
                }
            }.apply()
        }

    /** Bluetooth address of the bound scale, e.g. "C8:47:8C:12:34:56". */
    var scaleAddress: String?
        get() = prefs.getString("scale_address", null)
        set(v) = prefs.edit().putString("scale_address", v).apply()

    var compositionVersion: Int
        get() = prefs.getInt("composition_version", 1)
        set(v) = prefs.edit().putInt("composition_version", v).apply()

    var healthConnectEnabled: Boolean
        get() = prefs.getBoolean("hc_enabled", false)
        set(v) = prefs.edit().putBoolean("hc_enabled", v).apply()
}
