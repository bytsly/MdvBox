package com.example.mmdvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BoxSystem(val label: String) {
    WPSD("WPSD"),
    PI_STAR("Pi-Star"),
    J_STAR("J-STAR"),
    MMDVM("MMDVM"),
    UNKNOWN("未知系统")
}

/**
 * 盒子系统识别结果。
 */
data class BoxInfo(
    val system: BoxSystem,
    val version: String? = null,
    val title: String? = null
) {
    val displayName: String
        get() = if (version.isNullOrBlank()) system.label else "${system.label} v$version"

    /** 原生配置向导适配 WPSD 与 Pi-Star（两者后台配置页同源） */
    val wizardSupported: Boolean
        get() = system == BoxSystem.WPSD || system == BoxSystem.PI_STAR
}

/**
 * 通过首页（失败时退回后台登录页）识别盒子系统与版本。
 */
object BoxDetector {

    suspend fun detect(host: String, credentials: Credentials): BoxInfo = withContext(Dispatchers.IO) {
        val client = BoxHttpClient(host, credentials)
        val home = runCatching { client.get("/") }.getOrNull()
        home?.let { response ->
            infoFrom(response.body)?.let { return@withContext it }
        }
        val admin = runCatching { client.get("/admin/") }.getOrNull()
        admin?.let { response ->
            infoFrom(response.body)?.let { return@withContext it }
        }
        BoxInfo(BoxSystem.UNKNOWN, title = home?.title)
    }

    fun infoFrom(html: String): BoxInfo? {
        val system = classify(html) ?: return null
        return BoxInfo(system = system, version = versionOf(html), title = HttpText.titleOf(html))
    }

    fun classify(html: String): BoxSystem? {
        val text = html.lowercase()
        return when {
            text.contains("wpsd") || text.contains("w0chp") -> BoxSystem.WPSD
            text.contains("pi-star") || text.contains("pistar") -> BoxSystem.PI_STAR
            text.contains("j-star") || text.contains("jstar") -> BoxSystem.J_STAR
            text.contains("mmdvm") -> BoxSystem.MMDVM
            else -> null
        }
    }

    private val versionRegex = Regex(
        "(?i)(wpsd|pi[- ]?star|j[- ]?star)[^0-9<]{0,30}?(\\d+\\.\\d+(?:\\.\\d+)?)"
    )

    fun versionOf(html: String): String? =
        versionRegex.find(html)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
}
