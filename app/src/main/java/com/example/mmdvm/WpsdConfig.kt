package com.example.mmdvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

internal class BoxConfigException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal enum class WpsdEditorMode { FIELDS, TEXT }

/**
 * 一次读取到的 MMDVMHost 配置编辑会话。
 * FIELDS：Pi-Star 风格的逐项编辑器（Section[Key] 表单）。
 * TEXT：完整编辑器（textarea 内是整份 INI 文本）。
 */
internal data class WpsdEditor(
    val url: String,
    val mode: WpsdEditorMode,
    val form: HtmlForm,
    val iniText: String?,
    val values: Map<String, String>
) {
    fun actualKey(section: String, key: String): String? {
        val wanted = IniText.keyOf(section, key)
        values.keys.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        val normalized = normalizeKey(wanted)
        return values.keys.firstOrNull { normalizeKey(it) == normalized }
    }

    fun valueOf(section: String, key: String): String? =
        actualKey(section, key)?.let { values[it] }
}

internal fun normalizeKey(value: String): String =
    value.replace(" ", "").replace("_", "").lowercase()

/**
 * WPSD 配置读写：先定位 MMDVMHost 编辑页，再按原表单回放写入，最后重新读取校验。
 */
internal class WpsdConfigClient(
    private val host: String,
    private val credentials: Credentials
) {

    private val http = BoxHttpClient(host, credentials)

    suspend fun detectSystem(): BoxInfo = withContext(Dispatchers.IO) {
        BoxDetector.detect(host, credentials)
    }

    suspend fun openEditor(): WpsdEditor = withContext(Dispatchers.IO) {
        val tried = LinkedHashSet<String>()
        for (url in candidateUrls()) {
            if (!tried.add(url)) continue
            tryLoad(url)?.let { return@withContext it }
        }
        for (url in discoverEditorLinks()) {
            if (!tried.add(url)) continue
            tryLoad(url)?.let { return@withContext it }
        }
        throw BoxConfigException(
            "没有找到可用的 MMDVMHost 配置编辑页面。请确认后台账号密码正确，" +
                "并且能在电脑上正常打开 Admin → Advanced → 配置编辑器。"
        )
    }

    suspend fun save(editor: WpsdEditor, changes: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (changes.isEmpty()) throw BoxConfigException("没有需要保存的修改")

            val body: List<Pair<String, String>> = when (editor.mode) {
                WpsdEditorMode.FIELDS -> {
                    val overrides = changes.entries.associate { normalizeKey(it.key) to it.value }
                    editor.form.fields.map { field ->
                        val normalized = fieldCompoundKey(field.name)?.let { normalizeKey(it) }
                        field.name to (normalized?.let { overrides[it] } ?: field.value)
                    }
                }

                WpsdEditorMode.TEXT -> {
                    val textareaName = editor.form.textareaName
                        ?: throw BoxConfigException("配置页面结构异常：缺少配置文本域")
                    val updated = IniText.applyAll(editor.iniText.orEmpty(), changes)
                    editor.form.fields.map { field ->
                        field.name to if (field.name == textareaName) updated else field.value
                    }
                }
            }

            val response = http.postForm(editor.url, body)
            when {
                response.code == 401 -> throw BoxConfigException("后台账号或密码错误（HTTP 401）")
                response.code >= 400 -> throw BoxConfigException("写入失败（HTTP ${response.code}）")
                isDefaultPasswordWarning(response.body) -> throw BoxConfigException(DEFAULT_PASSWORD_HINT)
            }

            val reloaded = tryLoad(editor.url)
                ?: throw BoxConfigException("写入后无法重新读取配置，请到网页后台确认是否保存成功。")
            val mismatched = changes.filter { (key, expected) ->
                !sameValue(lookup(reloaded.values, key), expected)
            }
            if (mismatched.isNotEmpty()) {
                val names = mismatched.keys.take(3).joinToString("、") { it.replace('|', ' ') }
                throw BoxConfigException("写入后校验不一致（$names），请到网页后台确认。")
            }
        }

    /** 依次尝试候选地址；网络错误继续尝试，认证/默认密码问题直接抛出。 */
    private suspend fun tryLoad(url: String): WpsdEditor? {
        return try {
            loadEditor(url)
        } catch (e: BoxConfigException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadEditor(url: String): WpsdEditor? {
        val response = http.get(url)
        when {
            response.code == 401 -> throw BoxConfigException(
                "后台账号或密码错误（HTTP 401），请先在设备详情里修改保存的账号密码。"
            )
            response.code >= 400 -> return null
        }
        if (isDefaultPasswordWarning(response.body)) throw BoxConfigException(DEFAULT_PASSWORD_HINT)

        val form = HtmlFormParser.parse(response.body, response.finalUrl) ?: return null

        val iniFromTextarea = form.textareaText?.takeIf { it.contains("[General]") }
        if (iniFromTextarea != null) {
            val values = IniText.parse(iniFromTextarea)
            if (hasCallsign(values)) {
                return WpsdEditor(response.finalUrl, WpsdEditorMode.TEXT, form, iniFromTextarea, values)
            }
        }

        val values = LinkedHashMap<String, String>()
        form.fields.forEach { field ->
            val compound = fieldCompoundKey(field.name) ?: return@forEach
            values[compound] = field.value
        }
        if (!hasCallsign(values)) return null
        return WpsdEditor(response.finalUrl, WpsdEditorMode.FIELDS, form, null, values)
    }

    private fun hasCallsign(values: Map<String, String>): Boolean =
        values.keys.any { normalizeKey(it) == normalizeKey(IniText.keyOf("General", "Callsign")) }

    private fun candidateUrls(): List<String> {
        val paths = listOf(
            "/admin/expert/edit_mmdvmhost.php",
            "/admin/advanced/edit_mmdvmhost.php",
            "/admin/advanced/quick_edit_mmdvmhost.php",
            "/admin/advanced/full_edit_mmdvmhost.php",
            "/admin/expert/fulledit_mmdvmhost.php"
        )
        val password = runCatching { URLEncoder.encode(credentials.password, "UTF-8") }
            .getOrDefault(credentials.password)
        return paths.flatMap { listOf(it, "$it?pw=$password") }
    }

    /** 从后台首页导航里找 MMDVMHost 编辑入口，兼容不同固件的目录结构。 */
    private suspend fun discoverEditorLinks(): List<String> {
        val pages = listOf("/admin/", "/admin/advanced/", "/admin/expert/")
        val links = LinkedHashSet<String>()
        for (page in pages) {
            val response = runCatching { http.get(page) }.getOrNull() ?: continue
            if (response.code !in 200..399) continue
            HREF_REGEX.findAll(response.body).forEach { match ->
                val href = HttpText.unescape(match.groupValues[1]).trim()
                if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true)) {
                    return@forEach
                }
                val lower = href.lowercase()
                val looksLikeMmdvmHostEditor = lower.contains("mmdvmhost") ||
                    (lower.contains("mmdvm") && (lower.contains("edit") || lower.contains("editor")))
                if (looksLikeMmdvmHostEditor && !lower.endsWith(".css") && !lower.endsWith(".js")) {
                    links += http.absolute(resolvePath(page, href))
                }
            }
        }
        return links.take(8)
    }

    private fun resolvePath(page: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("/")) return href
        return page.substringBeforeLast('/') + "/" + href
    }

    private fun fieldCompoundKey(name: String): String? {
        if (!name.contains('[') || !name.endsWith(']')) return null
        val section = name.substringBefore('[').trim()
        val key = name.substringAfter('[').substringBeforeLast(']').trim()
        if (section.isEmpty() || key.isEmpty()) return null
        return IniText.keyOf(section, key)
    }

    private fun lookup(values: Map<String, String>, key: String): String? =
        values[key] ?: values.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?: values.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(key) }?.value

    private fun sameValue(actual: String?, expected: String?): Boolean {
        if (actual == null || expected == null) return false
        val left = actual.trim()
        val right = expected.trim()
        if (left == right) return true
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        return leftNumber != null && rightNumber != null && leftNumber == rightNumber
    }

    private fun isDefaultPasswordWarning(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("default password") || text.contains("默认密码")
    }

    private companion object {
        val HREF_REGEX = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        const val DEFAULT_PASSWORD_HINT =
            "盒子仍在使用出厂默认密码，系统不允许继续操作。请先登录网页后台修改密码，再使用配置向导。"
    }
}
