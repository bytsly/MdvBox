package com.example.mmdvm

import android.content.Context

data class Credentials(
    val username: String,
    val password: String
)

/**
 * 按设备（IP）分别保存后台登录账号，默认使用 Pi-Star / WPSD 的出厂账号。
 */
object CredentialStore {

    const val DEFAULT_USERNAME = "pi-star"
    const val DEFAULT_PASSWORD = "raspberry"

    private const val PREFS_NAME = "mmdvm_credentials"

    fun get(context: Context, host: String): Credentials {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Credentials(
            username = prefs.getString("user@$host", null) ?: DEFAULT_USERNAME,
            password = prefs.getString("pass@$host", null) ?: DEFAULT_PASSWORD
        )
    }

    fun save(context: Context, host: String, credentials: Credentials) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("user@$host", credentials.username)
            .putString("pass@$host", credentials.password)
            .apply()
    }
}
