package com.example.mmdvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.abs

internal class ConfigurePageException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal enum class ConfigureFieldKind { TEXT, HIDDEN, SELECT, CHECKBOX }

internal data class ConfigureOption(
    val value: String,
    val selected: Boolean,
    val text: String = ""
)

internal data class ConfigureField(
    val name: String,
    val kind: ConfigureFieldKind,
    val value: String,
    val checked: Boolean = false,
    val options: List<ConfigureOption> = emptyList(),
    val inputType: String = "text",
    /** 提交时真正使用的字段名。Pi-Star 的字段会被改名成 WPSD 的名字，这里保留原名用于回放。 */
    val postName: String = name
)

/**
 * 一次提交要改写的字段。values 对应文本/下拉框，radioValues 对应单选框选中值，
 * checkboxes 对应复选框是否勾选。未列出的字段按页面原值回放。
 */
internal data class ConfigureEdits(
    val values: Map<String, String> = emptyMap(),
    val radioValues: Map<String, String> = emptyMap(),
    val checkboxes: Map<String, Boolean> = emptyMap()
)

internal data class ConfigureForm(
    val url: String,
    val action: String,
    val fields: List<ConfigureField>
) {
    private fun named(name: String) = fields.filter { it.name.equals(name, ignoreCase = true) }

    fun has(name: String): Boolean = named(name).isNotEmpty()

    fun valueOf(name: String): String? =
        named(name).firstOrNull { it.kind != ConfigureFieldKind.CHECKBOX }?.value

    fun selectValue(name: String): String? =
        named(name).firstOrNull { it.kind == ConfigureFieldKind.SELECT }?.value

    fun radioValue(name: String): String? =
        named(name).firstOrNull { it.kind == ConfigureFieldKind.CHECKBOX && it.checked }?.value

    fun isChecked(name: String): Boolean =
        named(name).any { it.kind == ConfigureFieldKind.CHECKBOX && it.checked }

    /** 逻辑字段名对应的网页原始字段名（Pi-Star 用回原名提交）。 */
    fun postNameOf(name: String): String =
        named(name).firstOrNull()?.postName ?: name
}

/**
 * Pi-Star 与 WPSD 的配置页同源但字段名不同，这里把 Pi-Star 的字段名统一成 WPSD 的名字，
 * 提交回放时通过 ConfigureField.postName 还原成网页原名。
 */
internal object ConfigureFieldAliases {

    private val aliases: Map<String, String> = mapOf(
        // 基本设置
        "callsign" to "confCallsign",
        "dmrid" to "dmrId",
        "dmr_id" to "dmrId",
        "trxmode" to "trxMode",
        // 射频
        "frequency" to "confFREQ",
        "freq" to "confFREQ",
        "rxfrequency" to "confFREQrx",
        "txfrequency" to "confFREQtx",
        "dmrcc" to "dmrColorCode",
        // 调制解调器
        "modemtype" to "confHardware",
        "modemport" to "confPort",
        "modemspeed" to "confHardwareSpeed",
        "baudrate" to "confHardwareSpeed",
        // 工作模式（Pi-Star 直接用模式名做 checkbox 字段名）
        "dmr" to "MMDVMModeDMR",
        "dstar" to "MMDVMModeDSTAR",
        "systemfusion" to "MMDVMModeFUSION",
        "p25" to "MMDVMModeP25",
        "nxdn" to "MMDVMModeNXDN",
        "pocsag" to "MMDVMModePOCSAG",
        "ysf2dmr" to "MMDVMModeYSF2DMR",
        "ysf2nxdn" to "MMDVMModeYSF2NXDN",
        "ysf2p25" to "MMDVMModeYSF2P25",
        "dmr2ysf" to "MMDVMModeDMR2YSF",
        "dmr2nxdn" to "MMDVMModeDMR2NXDN",
        "cwid" to "MMDVMModeCWID"
    )

    fun unify(fields: List<ConfigureField>): List<ConfigureField> = fields.map { field ->
        val target = aliases[field.name.lowercase()]
        if (target == null) field else field.copy(name = target, postName = field.name)
    }
}

/**
 * 从 Wi-Fi 页面里解析盒子扫描到的 SSID：支持下拉框选项、wpa_cli 文本行和扫描结果表格三种形式。
 */
internal object WifiScanParser {

    private val rowRegex = Regex(
        "<tr\\b[^>]*>(.*?)</tr>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val cellRegex = Regex(
        "<t[dh]\\b[^>]*>(.*?)</t[dh]>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tagRegex = Regex("<[^>]+>")
    private val bssidRegex = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$", RegexOption.IGNORE_CASE)
    private val scanLineRegex = Regex(
        "^([0-9a-f]{2}:){5}[0-9a-f]{2}\\s+\\d+\\s+-?\\d+\\s+(\\[[^\\]]*\\]\\s*)*(.+)$",
        RegexOption.IGNORE_CASE
    )
    // Pi-Star 扫描结果里每行一个 Select 按钮：onclick="AddScanned('SSID')"
    private val addScannedRegex = Regex(
        "AddScanned\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)",
        RegexOption.IGNORE_CASE
    )
    // WPSD 扫描结果：每个网络一个隐藏表单 <input type="hidden" name="ssid" value="ZHY">
    private val wpsdSsidRegex = Regex(
        "<input\\b[^>]*name\\s*=\\s*[\"']ssid[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val valueAttrRegex = Regex(
        "value\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
        RegexOption.IGNORE_CASE
    )

    fun parse(html: String, form: ConfigureForm?): List<String> {
        val found = linkedSetOf<String>()
        // 0. Pi-Star 扫描结果：直接从 AddScanned('SSID') 里取，最准确
        val added = addScannedRegex.findAll(html)
            .map { HttpText.unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (added.isNotEmpty()) return added.distinct()
        // 0.5 WPSD 扫描结果：隐藏表单里的 name="ssid" value="SSID"
        val wpsd = wpsdSsidRegex.findAll(html)
            .mapNotNull { tag ->
                val match = valueAttrRegex.find(tag.value) ?: return@mapNotNull null
                val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                HttpText.unescape(raw).trim().takeIf { it.isNotEmpty() }
            }
            .toList()
        if (wpsd.isNotEmpty()) return wpsd.distinct()
        // 1. SSID 下拉框里列出的网络
        form?.fields?.forEach { field ->
            if (field.kind == ConfigureFieldKind.SELECT && field.name.contains("ssid", true)) {
                field.options.forEach { option ->
                    option.value.trim().takeIf { it.isNotEmpty() }?.let(found::add)
                }
            }
        }
        // 2. wpa_cli scan_results 的纯文本行：BSSID 频率 信号 [加密] SSID
        html.lineSequence().forEach { line ->
            scanLineRegex.find(line.trim())?.let { match ->
                val ssid = match.groupValues[3].trim()
                if (ssid.isNotEmpty() && !bssidRegex.matches(ssid)) found += ssid
            }
        }
        // 3. 扫描结果表格：取每行最后一格作为 SSID
        if (html.contains("ssid", true) &&
            (html.contains("signal", true) || html.contains("bssid", true))
        ) {
            rowRegex.findAll(html).forEach { row ->
                val cells = cellRegex.findAll(row.groupValues[1])
                    .map { cell ->
                        cell.groupValues[1]
                            .replace("&nbsp;", " ")
                            .replace(tagRegex, "")
                            .trim()
                    }
                    .toList()
                val candidate = cells.lastOrNull() ?: return@forEach
                if (candidate.length in 1..32 &&
                    !bssidRegex.matches(candidate) &&
                    !candidate.equals("ssid", true) &&
                    candidate.toIntOrNull() == null
                ) {
                    found += candidate
                }
            }
        }
        return found.toList()
    }
}

/**
 * 解析 WPSD/Pi-Star 的 Configuration 主表单（含 confCallsign / MMDVMModeDMR 的那个 form）。
 */
internal object ConfigureFormParser {

    private val formRegex = Regex(
        "<form\\b([^>]*)>(.*?)</form>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val fieldRegex = Regex(
        "<input\\b[^>]*>|<select\\b[^>]*>.*?</select>|<textarea\\b[^>]*>.*?</textarea>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val optionRegex = Regex(
        "<option\\b([^>]*)>(.*?)</option>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attrRegex = Regex(
        "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))"
    )
    private val skippedTypes = setOf("submit", "reset", "button", "image", "file")

    fun parse(html: String, pageUrl: String): ConfigureForm? = parseAny(html, pageUrl, null)

    /**
     * 通用表单解析：required 为 null 时按主配置页特征字段匹配；
     * 否则只要表单里出现任意一个 required 关键字就采用（用于 Wi-Fi 等独立页面）。
     */
    fun parseAny(html: String, pageUrl: String, required: List<String>?): ConfigureForm? {
        formRegex.findAll(html).forEach { match ->
            val inner = match.groupValues[2]
            val parsed = ConfigureFieldAliases.unify(parseFields(inner))
            val fields = parsed
            if (fields.isEmpty()) return@forEach
            val names = fields.map { it.name.lowercase() }
            val rawNames = parsed.map { it.postName.lowercase() }
            val matched = if (required == null) {
                // WPSD 用 confCallsign / MMDVMModeDMR；Pi-Star 用 callsign
                names.any { it == "confcallsign" } ||
                    names.any { it == "mmdvmmodedmr" } ||
                    rawNames.any { it == "callsign" }
            } else {
                // 只认字段名，避免被页面里的导航链接/说明文字里的 "wifi" 误导（例如 APRS 页面）
                (names + rawNames).any { name -> required.any { name.contains(it) } }
            }
            if (!matched) return@forEach
            val attrs = attributes(match.groupValues[1])
            return ConfigureForm(
                url = pageUrl,
                action = resolve(pageUrl, attrs["action"].orEmpty()),
                fields = fields
            )
        }
        return null
    }

    fun buildBody(form: ConfigureForm, edits: ConfigureEdits): List<Pair<String, String>> {
        fun valueEdit(name: String): String? =
            edits.values.entries.firstOrNull { it.key.equals(name, true) }?.value

        fun radioEdit(name: String): String? =
            edits.radioValues.entries.firstOrNull { it.key.equals(name, true) }?.value

        fun checkboxEdit(name: String): Boolean? =
            edits.checkboxes.entries.firstOrNull { it.key.equals(name, true) }?.value

        val body = mutableListOf<Pair<String, String>>()
        val rendered = form.fields.map { it.name.lowercase() }.toSet()
        form.fields.forEach { field ->
            when (field.kind) {
                ConfigureFieldKind.HIDDEN,
                ConfigureFieldKind.TEXT,
                ConfigureFieldKind.SELECT -> {
                    val edited = valueEdit(field.name)
                    // 密码框网页通常不回显原值；用户没填时保持原密码，避免提交空值把它清掉
                    if (edited != null || field.inputType != "password") {
                        body += field.postName to (edited ?: field.value)
                    }
                }

                ConfigureFieldKind.CHECKBOX -> {
                    val radioTarget = radioEdit(field.name)
                    val desired = when {
                        radioTarget != null -> field.value.equals(radioTarget, ignoreCase = true)
                        checkboxEdit(field.name) != null -> checkboxEdit(field.name) == true
                        else -> field.checked
                    }
                    if (desired) body += field.postName to field.value.ifBlank { "ON" }
                }
            }
        }

        // 页面未渲染的字段（例如未启用任何工作模式时隐藏的频率输入框）按字段名直接补交，
        // 使向导设置的值不会因为网页没有显示输入框而丢失。
        edits.values.forEach { (name, value) ->
            if (name.lowercase() !in rendered && value.isNotBlank()) {
                body += form.postNameOf(name) to value
            }
        }
        edits.radioValues.forEach { (name, value) ->
            if (name.lowercase() !in rendered) body += form.postNameOf(name) to value
        }
        edits.checkboxes.forEach { (name, checked) ->
            if (name.lowercase() !in rendered && checked) body += form.postNameOf(name) to "ON"
        }
        return body
    }

    private fun parseFields(inner: String): List<ConfigureField> {
        val fields = mutableListOf<ConfigureField>()
        fieldRegex.findAll(inner).forEach { match ->
            val tag = match.value
            when {
                tag.startsWith("<input", ignoreCase = true) -> {
                    val attrs = attributes(tag)
                    val name = attrs["name"]?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    val type = attrs["type"]?.lowercase().orEmpty()
                    if (type in skippedTypes) return@forEach
                    val kind = when (type) {
                        "hidden" -> ConfigureFieldKind.HIDDEN
                        "checkbox", "radio" -> ConfigureFieldKind.CHECKBOX
                        else -> ConfigureFieldKind.TEXT
                    }
                    fields += ConfigureField(
                        name = name,
                        kind = kind,
                        value = attrs["value"]
                            ?: if (kind == ConfigureFieldKind.CHECKBOX) "ON" else "",
                        checked = attrs.containsKey("checked"),
                        inputType = type
                    )
                }

                tag.startsWith("<select", ignoreCase = true) -> {
                    val attrs = attributes(tag.substringBefore('>'))
                    val name = attrs["name"]?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    val options = optionRegex.findAll(tag).map { option ->
                        val optionAttrs = attributes(option.groupValues[1])
                        val value = optionAttrs["value"]
                            ?: HttpText.unescape(option.groupValues[2]).trim()
                        ConfigureOption(
                            value = value,
                            selected = optionAttrs.containsKey("selected"),
                            text = HttpText.unescape(option.groupValues[2]).trim()
                        )
                    }.toList()
                    val selected = options.firstOrNull { it.selected }?.value
                        ?: options.firstOrNull()?.value.orEmpty()
                    fields += ConfigureField(
                        name = name,
                        kind = ConfigureFieldKind.SELECT,
                        value = selected,
                        options = options
                    )
                }

                tag.startsWith("<textarea", ignoreCase = true) -> {
                    val attrs = attributes(tag.substringBefore('>'))
                    val name = attrs["name"]?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    val text = tag.substringAfter('>').substringBeforeLast("</textarea>")
                    fields += ConfigureField(name, ConfigureFieldKind.TEXT, HttpText.unescape(text))
                }
            }
        }
        return fields
    }

    private fun resolve(pageUrl: String, action: String): String {
        if (action.isBlank()) return pageUrl
        if (action.startsWith("http://") || action.startsWith("https://")) return action
        return runCatching { URI(pageUrl).resolve(action).toString() }.getOrDefault(pageUrl)
    }

    private fun attributes(tag: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        attrRegex.findAll(tag).forEach { match ->
            val name = match.groupValues[1].lowercase()
            val value = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                else -> match.groupValues[4]
            }
            result[name] = HttpText.unescape(value)
        }
        return result
    }
}

/**
 * 模拟电脑浏览器：GET 后台 Configuration 页 -> 整表 POST（等同于手工填空后点 Apply Changes）。
 */
internal class WpsdConfigurePageClient(
    private val host: String,
    private val credentials: Credentials
) {

    private val http = BoxHttpClient(host, credentials)

    suspend fun detectSystem(): BoxInfo = withContext(Dispatchers.IO) {
        BoxDetector.detect(host, credentials)
    }

    suspend fun load(): ConfigureForm = withContext(Dispatchers.IO) {
        var problem = "未知原因"
        for (path in CANDIDATE_PATHS) {
            val response = try {
                http.get(path)
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
                continue
            }
            when {
                response.code == 401 -> throw ConfigurePageException(
                    "后台账号或密码错误（HTTP 401），请先在设备详情里修改保存的账号密码。"
                )

                response.code >= 400 -> {
                    problem = "HTTP ${response.code}"
                    continue
                }
            }
            if (isDefaultPasswordWarning(response.body)) {
                throw ConfigurePageException(DEFAULT_PASSWORD_HINT)
            }
            ConfigureFormParser.parse(response.body, response.finalUrl)?.let {
                return@withContext it
            }
            problem = "页面中没有找到配置表单"
        }
        throw ConfigurePageException(
            "无法打开 WPSD 网页配置页（$problem）。请确认后台账号密码正确，" +
                "并且能在电脑浏览器里打开 /admin/configure.php。"
        )
    }

    /**
     * Wi-Fi 在独立页面时（如 /admin/wifi.php），单独取回那个页面的表单；
     * 找不到就返回 null，向导会退回到主配置页里的网络字段。
     */
    suspend fun loadNetwork(): ConfigureForm? = withContext(Dispatchers.IO) {
        // 优先找 Wi-Fi 专用页（WPSD: /admin/wifi-manager.php）
        for (path in NETWORK_PATHS) {
            fetchNetworkForm(path)?.let { return@withContext it }
        }
        // 其次看主配置页的 “Wi-Fi” 标签里是否有 ssid 输入框
        val main = runCatching { http.get(CONFIGURE_PATH) }.getOrNull()
        if (main != null && main.code in 200..399) {
            ConfigureFormParser.parseAny(main.body, main.finalUrl, WIFI_FIELD_KEYS)
                ?.let { return@withContext it }
            wifiLinks(main.body, main.finalUrl).forEach { url ->
                fetchNetworkForm(url)?.let { return@withContext it }
            }
        }
        null
    }

    /** Wi-Fi 页面提交后盒子可能立即切换无线网络，因此只做响应校验，不回读。 */
    suspend fun applyNetwork(form: ConfigureForm, edits: ConfigureEdits): Boolean =
        withContext(Dispatchers.IO) {
            // Pi-Star 只有点了 “Save (and connect)” 才会写入，按钮名必须一起提交
            val extra = if (isPistarWifi(form)) {
                listOf("SaveWPAPSKSettings" to "Save (and connect)")
            } else {
                emptyList()
            }
            val body = wifiBody(form, edits, extra)
            if (body.isEmpty()) return@withContext false
            val response = try {
                http.postForm(form.action.ifBlank { form.url }, body)
            } catch (e: java.net.SocketTimeoutException) {
                throw ConfigurePageException(
                    "提交超时：盒子多半已经收到 Wi-Fi 设置并正在切换网络。" +
                        "请断电重启盒子，等它连上路由器后再回来继续。"
                )
            }
            when {
                response.code == 401 -> throw ConfigurePageException(
                    "后台账号或密码错误（HTTP 401），请先在设备详情里修改保存的账号密码。"
                )

                response.code >= 400 -> throw ConfigurePageException(
                    "Wi-Fi 设置提交失败（HTTP ${response.code}），请到网页后台确认。"
                )
            }
            val text = response.body.lowercase()
            if (text.contains("unable to write") || text.contains("changes failed")) {
                throw ConfigurePageException("盒子拒绝写入 Wi-Fi 设置，请查看网页后台的提示信息。")
            }
            true
        }

    /**
     * 让盒子自己扫描周边 Wi-Fi。
     * Pi-Star：点页面上的 “Scan for Networks (10 secs)”，整表 POST 后扫描结果直接渲染在响应里；
     * 其它系统：尝试常见的扫描入口后重新读取页面解析。
     */
    suspend fun scanWifi(form: ConfigureForm): List<String> = withContext(Dispatchers.IO) {
        val page = form.action.ifBlank { form.url }
        if (isPistarWifi(form)) {
            val body = wifiBody(form, ConfigureEdits(), listOf("Scan" to "Scan for Networks"))
            val response = runCatching { http.postForm(page, body) }.getOrNull()
            if (response != null && response.code in 200..399) {
                val found = WifiScanParser.parse(response.body, null)
                if (found.isNotEmpty()) return@withContext found
            }
        }
        // WPSD：WiFi Manager 页点 “Scan for Networks”，POST action=scan，结果直接渲染在响应里
        if (isWpsdWifi(form)) {
            val response = runCatching { http.postForm(page, listOf("action" to "scan")) }.getOrNull()
            if (response != null && response.code in 200..399) {
                val found = WifiScanParser.parse(response.body, null)
                if (found.isNotEmpty()) return@withContext found
            }
        }
        listOf("$page?scan=1", "$page?action=scan").forEach { url ->
            runCatching { http.get(url) }
        }
        runCatching { http.postForm(page, listOf("action" to "scan", "scan" to "1")) }
        // 盒子执行扫描需要几秒，给一点时间再取结果
        delay(2000)
        val response = runCatching { http.get(form.url.ifBlank { page }) }.getOrNull()
            ?: return@withContext emptyList()
        if (response.code !in 200..399) return@withContext emptyList()
        val fresh = ConfigureFormParser.parseAny(response.body, response.finalUrl, WIFI_FIELD_KEYS)
        WifiScanParser.parse(response.body, fresh)
    }

    /** WPSD 的整机配置备份页是否可用（/admin/config_backup.php）。 */
    suspend fun configBackupSupported(): Boolean = withContext(Dispatchers.IO) {
        val response = runCatching { http.get("/admin/config_backup.php") }.getOrNull()
            ?: return@withContext false
        response.code in 200..399 &&
            response.body.contains("value=\"download\"", ignoreCase = true)
    }

    /** 下载盒子整机配置备份（zip），返回文件名与内容。 */
    suspend fun downloadConfigBackup(): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val result = http.postFormBytes(
            "/admin/config_backup.php",
            listOf("action" to "download")
        )
        if (result.code !in 200..399) {
            throw ConfigurePageException("下载备份失败（HTTP ${result.code}），请确认后台能打开备份页。")
        }
        if (!result.looksLikeZip) {
            throw ConfigurePageException("盒子没有返回 zip 文件，可能是会话过期或备份服务不可用。")
        }
        val fallback = "WPSD_Config_" +
            java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US)
                .format(java.util.Date()) + ".zip"
        (result.fileName ?: fallback) to result.bytes
    }

    /** 把 zip 上传到盒子并还原（会覆盖盒子配置并重启相关服务）。 */
    suspend fun restoreConfigBackup(fileName: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val response = http.postMultipartFile(
                pathOrUrl = "/admin/config_backup.php",
                fields = listOf("action" to "restore"),
                fileField = "fileToUpload",
                fileName = fileName,
                file = bytes
            )
            if (response.code !in 200..399) {
                throw ConfigurePageException("还原失败（HTTP ${response.code}），请到网页后台重试。")
            }
            true
        }

    /** Pi-Star 的 Wi-Fi 配置页（iframe 里的 wifi.php?page=wpa_conf）。 */
    private fun isPistarWifi(form: ConfigureForm): Boolean =
        (form.action + form.url).contains("page=wpa_conf", ignoreCase = true) ||
            (form.has("Networks") &&
                form.fields.any { Regex("^ssid\\d+$", RegexOption.IGNORE_CASE).matches(it.postName) })

    /** WPSD 的 WiFi Connection Manager 页：新增网络用 action=add + ssid + passphrase。 */
    private fun isWpsdWifi(form: ConfigureForm): Boolean =
        (form.action + form.url).contains("wifi-manager", ignoreCase = true) ||
            (form.has("passphrase") && form.valueOf("action").equals("add", ignoreCase = true))

    /**
     * 组装 Wi-Fi 提交表单。Pi-Star 用隐藏字段 Networks 告诉后台有几个 Wi-Fi，
     * 网页里这个值由脚本填，脚本请求必须自己算，否则会被当成 0 个网络。
     */
    private fun wifiBody(
        form: ConfigureForm,
        edits: ConfigureEdits,
        extra: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val body = ConfigureFormParser.buildBody(form, edits).toMutableList()
        if (isPistarWifi(form)) {
            body.removeAll { it.first.equals("Networks", true) }
            body += "Networks" to pistarNetworkCount(form, edits).toString()
        }
        body += extra
        return body
    }

    private fun pistarNetworkCount(form: ConfigureForm, edits: ConfigureEdits): Int =
        form.fields
            .filter { Regex("^ssid\\d+$", RegexOption.IGNORE_CASE).matches(it.postName) }
            .count { field ->
                val edited = edits.values.entries
                    .firstOrNull { it.key.equals(field.name, true) }?.value
                (edited ?: field.value).isNotBlank()
            }

    private suspend fun fetchNetworkForm(pathOrUrl: String): ConfigureForm? {
        val response = runCatching { http.get(pathOrUrl) }.getOrNull() ?: return null
        if (response.code !in 200..399) return null
        val form = ConfigureFormParser.parseAny(response.body, response.finalUrl, WIFI_FIELD_KEYS)
            ?: return null
        // 必须真的能填 SSID：WPSD 的旧版 wifi.php 只剩一个“国家代码”，不能当成 Wi-Fi 配置页
        val hasSsid = form.fields.any { field ->
            SSID_FIELD_KEYS.any { field.name.contains(it, ignoreCase = true) }
        }
        return if (hasSsid) form else null
    }

    private fun wifiLinks(html: String, pageUrl: String): List<String> =
        Regex("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .filter {
                it.contains("wifi", true) || it.contains("wlan", true) || it.contains("network", true)
            }
            .map { link -> runCatching { URI(pageUrl).resolve(link).toString() }.getOrDefault(link) }
            .distinct()
            .take(5)
            .toList()

    suspend fun apply(form: ConfigureForm, edits: ConfigureEdits): ConfigureForm =
        withContext(Dispatchers.IO) {
            val body = ConfigureFormParser.buildBody(form, edits)
            if (body.isEmpty()) throw ConfigurePageException("配置表单为空，无法提交")

            // 盒子重建配置可能要几十秒，读超时不代表失败：下面会用重新读取来判定
            val response = try {
                http.postForm(form.action.ifBlank { form.url }, body)
            } catch (e: java.net.SocketTimeoutException) {
                null
            }
            if (response != null) {
                when {
                    response.code == 401 -> throw ConfigurePageException(
                        "后台账号或密码错误（HTTP 401），请先在设备详情里修改保存的账号密码。"
                    )

                    response.code >= 400 -> throw ConfigurePageException(
                        "提交失败（HTTP ${response.code}），请到网页后台确认。"
                    )
                }
                val text = response.body.lowercase()
                if (text.contains("unable to write configuration") ||
                    text.contains("changes failed") ||
                    text.contains("configuration error")
                ) {
                    throw ConfigurePageException("盒子拒绝写入配置，请查看网页后台的提示信息。")
                }
            }

            var mismatch = emptyList<String>()
            repeat(VERIFY_ATTEMPTS) { attempt ->
                val reloaded = runCatching { load() }.getOrNull()
                if (reloaded != null) {
                    mismatch = verify(reloaded, edits)
                    if (mismatch.isEmpty()) return@withContext reloaded
                }
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_INTERVAL_MS)
            }
            throw ConfigurePageException(
                "配置已提交，但重新读取校验未通过" +
                    (if (mismatch.isEmpty()) "（盒子可能仍在重启服务）" else
                        "（${mismatch.take(3).joinToString("、")}）") +
                    "，请稍后到网页后台确认。"
            )
        }

    private fun verify(form: ConfigureForm, edits: ConfigureEdits): List<String> {
        val mismatches = mutableListOf<String>()

        edits.values.forEach { (name, expected) ->
            val field = form.fields.firstOrNull { it.name.equals(name, true) } ?: return@forEach
            if (field.inputType == "password") return@forEach
            if (!sameValue(field.value, expected)) mismatches += name
        }
        edits.radioValues.forEach { (name, expected) ->
            val actual = form.radioValue(name) ?: return@forEach
            if (!actual.equals(expected, ignoreCase = true)) mismatches += name
        }
        edits.checkboxes.forEach { (name, expected) ->
            if (!form.has(name)) return@forEach
            if (form.isChecked(name) != expected) mismatches += name
        }
        return mismatches
    }

    private fun sameValue(actual: String, expected: String): Boolean {
        val left = actual.trim()
        val right = expected.trim()
        if (left.equals(right, ignoreCase = true)) return true
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        return leftNumber != null && rightNumber != null && abs(leftNumber - rightNumber) < 0.001
    }

    private fun isDefaultPasswordWarning(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("default password") || text.contains("默认密码")
    }

    private companion object {
        val CANDIDATE_PATHS = listOf(
            "/admin/configure.php",
            "/admin/configuration.php",
            "/admin/config.php"
        )
        const val CONFIGURE_PATH = "/admin/configure.php"
        val NETWORK_PATHS = listOf(
            // WPSD 自己的 Wi-Fi 管理页
            "/admin/wifi-manager.php",
            // Pi-Star：配置页里 iframe 的 Wi-Fi 页，带 page 参数才有表单
            "/admin/wifi.php?page=wpa_conf",
            "/admin/wifi.php",
            "/admin/network.php",
            "/admin/wlan.php",
            "/admin/configure.php?tab=wifi",
            "/wifi.php"
        )
        val WIFI_FIELD_KEYS = listOf("ssid", "psk", "passphrase", "wlan")
        /** 判断“这一页真的能配 Wi-Fi”用：仅有 wifiCountryCode 之类的字段不算。 */
        val SSID_FIELD_KEYS = listOf("ssid", "psk", "passphrase")
        const val VERIFY_ATTEMPTS = 5
        const val VERIFY_INTERVAL_MS = 3000L
        const val DEFAULT_PASSWORD_HINT =
            "盒子仍在使用出厂默认密码，系统不允许继续操作。请先登录网页后台修改密码，再使用配置向导。"
    }
}
