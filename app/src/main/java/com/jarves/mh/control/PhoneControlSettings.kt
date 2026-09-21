package com.jarves.mh.control

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Runtime values injected into the agent's Linux environment at launch time.
 *
 * The agent process (Claude Code / DeepSeek / Antigravity) inherits these
 * environment variables, so the `phone` helper script inside the runtime can
 * reach the control server without any extra configuration files.
 */
object PhoneControlRuntime {
    @Volatile var port: Int = 0
    @Volatile var token: String = ""

    fun inject(environment: MutableMap<String, String>) {
        if (port > 0) {
            environment["MH_PHONE_PORT"] = port.toString()
            environment["MH_PHONE_TOKEN"] = token
        }
    }
}

/**
 * Persisted preferences for the "agent controls the phone" feature.
 *
 * - [enabled]          master switch. The control server only binds while this
 *                      is true, so a disabled feature has zero attack surface.
 * - [requireToken]     when true, every request must carry X-Access-Token.
 *                      Loopback is shared by all apps on Android, so a token
 *                      stops unrelated apps from driving the phone through us.
 */
class PhoneControlSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("phone_control", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var requireToken: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_TOKEN, true)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_TOKEN, value).apply()

    /** Creates a random token once and keeps it for the lifetime of the install. */
    fun issueTokenIfNeeded(): String {
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REQUIRE_TOKEN = "require_token"
        private const val KEY_TOKEN = "token"
    }
}