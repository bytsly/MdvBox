package com.example.mmdvm

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

internal data class BoxResponse(
    val code: Int,
    val body: String,
    val finalUrl: String,
    val contentType: String?
) {
    val title: String? get() = HttpText.titleOf(body)
}

/** 二进制响应（配置备份 zip 之类）。 */
internal data class BoxBinary(
    val code: Int,
    val bytes: ByteArray,
    val fileName: String?,
    val contentType: String?
) {
    val looksLikeZip: Boolean
        get() = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}

/**
 * 盒子后台的极简 HTTP 客户端：预置 Basic 认证、维护 Cookie 会话（CSRF 依赖会话），
 * 并按 HTTP 头 / meta 声明自动解码页面。
 */
internal class BoxHttpClient(
    private val host: String,
    private val credentials: Credentials
) {

    private val cookies = LinkedHashMap<String, String>()

    suspend fun get(pathOrUrl: String): BoxResponse = withContext(Dispatchers.IO) {
        execute("GET", absolute(pathOrUrl), null)
    }

    suspend fun postForm(pathOrUrl: String, fields: List<Pair<String, String>>): BoxResponse =
        withContext(Dispatchers.IO) {
            execute("POST", absolute(pathOrUrl), encodeForm(fields))
        }

    /** 提交表单并取回二进制内容（WPSD 的配置备份 zip）。 */
    suspend fun postFormBytes(pathOrUrl: String, fields: List<Pair<String, String>>): BoxBinary =
        withContext(Dispatchers.IO) {
            val connection = open(
                "POST",
                absolute(pathOrUrl),
                encodeForm(fields).toByteArray(Charsets.UTF_8),
                "application/x-www-form-urlencoded"
            )
            try {
                readBinary(connection)
            } finally {
                connection.disconnect()
            }
        }

    /** multipart 上传（WPSD 还原配置用 fileToUpload）。 */
    suspend fun postMultipartFile(
        pathOrUrl: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        file: ByteArray
    ): BoxResponse = withContext(Dispatchers.IO) {
        val boundary = "----MmdvmBox${System.currentTimeMillis()}"
        val payload = java.io.ByteArrayOutputStream()
        fun write(text: String) = payload.write(text.toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write(value)
            write("\r\n")
        }
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n")
        write("Content-Type: application/zip\r\n\r\n")
        payload.write(file)
        write("\r\n--$boundary--\r\n")
        val connection = open(
            "POST",
            absolute(pathOrUrl),
            payload.toByteArray(),
            "multipart/form-data; boundary=$boundary"
        )
        try {
            readText(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeForm(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }

    fun absolute(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
        pathOrUrl.startsWith("/") -> "http://$host$pathOrUrl"
        else -> "http://$host/$pathOrUrl"
    }

    private fun open(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            // 盒子（树莓派 Zero 等）重建配置、重启服务很慢，读超时要给足
            connectTimeout = 8000
            readTimeout = 90000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Authorization", basicAuth())
            setRequestProperty("User-Agent", "MmdvmBox/1.0 (Android)")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            if (cookies.isNotEmpty()) {
                setRequestProperty(
                    "Cookie",
                    cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                )
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                setRequestProperty("Referer", url)
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body) }
        }
        return connection
    }

    private fun execute(method: String, url: String, body: String?): BoxResponse {
        val connection = open(method, url, body?.toByteArray(Charsets.UTF_8), null)
        try {
            return readText(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(connection: HttpURLConnection): BoxResponse {
        val code = connection.responseCode
        storeCookies(connection)
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readLimited(MAX_BODY_BYTES) } ?: ByteArray(0)
        return BoxResponse(
            code = code,
            body = HttpText.decode(bytes, connection.contentType),
            finalUrl = connection.url.toString(),
            contentType = connection.contentType
        )
    }

    private fun readBinary(connection: HttpURLConnection): BoxBinary {
        val code = connection.responseCode
        storeCookies(connection)
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readLimited(MAX_BINARY_BYTES) } ?: ByteArray(0)
        return BoxBinary(
            code = code,
            bytes = bytes,
            fileName = fileNameOf(connection.getHeaderField("Content-Disposition")),
            contentType = connection.contentType
        )
    }

    private fun fileNameOf(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val match = Regex(
            "filename\\*?=(?:UTF-8''|\")([^\";\\r\\n]+)",
            RegexOption.IGNORE_CASE
        ).find(disposition) ?: return null
        return match.groupValues[1].trim().trim('"').ifBlank { null }
    }

    private fun basicAuth(): String {
        val raw = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun storeCookies(connection: HttpURLConnection) {
        connection.headerFields.forEach { (name, values) ->
            if (!name.equals("Set-Cookie", ignoreCase = true)) return@forEach
            values.orEmpty().forEach { header ->
                // 多个 Cookie 可能合并在同一行，按 ", name=" 拆分，避免 Expires 中的逗号误伤
                header.split(COOKIE_SPLIT).forEach { part ->
                    val pair = part.substringBefore(';').trim()
                    if (pair.isEmpty() || !pair.contains('=')) return@forEach
                    val cookieName = pair.substringBefore('=').trim()
                    val cookieValue = pair.substringAfter('=').trim()
                    if (cookieName.isNotEmpty()) cookies[cookieName] = cookieValue
                }
            }
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < maxBytes) {
            val read = read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, minOf(read, maxBytes - output.size()))
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_BINARY_BYTES = 8 * 1024 * 1024
        val COOKIE_SPLIT = Regex(",(?=\\s*[A-Za-z0-9_\\-]+=)")
    }
}

/**
 * 页面文本解码：优先 HTTP 头声明的 charset，其次 meta 声明；
 * 都未声明时先按 UTF-8，出现非法字节再退回 GBK。
 */
internal object HttpText {

    private val headerCharsetRegex =
        Regex("content-type:[^\\r\\n]*?charset=[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE)
    private val metaCharsetRegex =
        Regex("<meta[^>]+charset=[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE)
    private val titleRegex =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun decode(bytes: ByteArray, contentType: String? = null): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = (
            contentType?.let { headerCharsetRegex.find("content-type: $it") }?.groupValues?.getOrNull(1)
                ?: headerCharsetRegex.find(head)?.groupValues?.getOrNull(1)
                ?: metaCharsetRegex.find(head)?.groupValues?.getOrNull(1)
            )?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
        if (declared != null) return String(bytes, declared)

        val utf8 = String(bytes, Charsets.UTF_8)
        return if ('\uFFFD' in utf8) {
            runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
        } else {
            utf8
        }
    }

    fun titleOf(html: String): String? = titleRegex.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { unescape(it).trim() }
        ?.takeIf { it.isNotEmpty() }

    fun unescape(text: String): String {
        if ('&' !in text) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '&') {
                val end = text.indexOf(';', index + 1)
                if (end in (index + 2)..(index + 12)) {
                    val decoded = decodeEntity(text.substring(index + 1, end))
                    if (decoded != null) {
                        builder.append(decoded)
                        index = end + 1
                        continue
                    }
                }
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.startsWith("#")) {
            val code = runCatching {
                if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                    entity.substring(2).toInt(16)
                } else {
                    entity.substring(1).toInt()
                }
            }.getOrNull() ?: return null
            return runCatching { String(Character.toChars(code)) }.getOrNull()
        }
        return when (entity.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> "\u00A0"
            else -> null
        }
    }
}
