package com.example.mmdvm.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mmdvm.BoxInfo
import com.example.mmdvm.BoxSystem
import com.example.mmdvm.ConfigBackupStore
import com.example.mmdvm.ConfigSnapshot
import com.example.mmdvm.ConfigureEdits
import com.example.mmdvm.ConfigureField
import com.example.mmdvm.ConfigureFieldKind
import com.example.mmdvm.ConfigureForm
import com.example.mmdvm.ConfigurePageException
import com.example.mmdvm.CredentialStore
import com.example.mmdvm.MmdvmDevice
import com.example.mmdvm.WpsdConfigurePageClient
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import android.widget.Toast

private enum class WizardStatus { Detecting, Loading, Ready, Unsupported, Error, Saved }

private const val FIELD_CALLSIGN = "confCallsign"
private const val FIELD_DMR_ID = "dmrId"
private const val FIELD_TRX_MODE = "trxMode"
private const val FIELD_FREQ = "confFREQ"
private const val FIELD_FREQ_RX = "confFREQrx"
private const val FIELD_FREQ_TX = "confFREQtx"
private const val FIELD_COLOR_CODE = "dmrColorCode"

private val MODE_FIELDS = listOf(
    "MMDVMModeDMR" to "DMR",
    "MMDVMModeDSTAR" to "D-Star",
    "MMDVMModeFUSION" to "System Fusion / YSF",
    "MMDVMModeP25" to "P25",
    "MMDVMModeNXDN" to "NXDN",
    "MMDVMModePOCSAG" to "POCSAG",
    "MMDVMModeYSF2DMR" to "YSF → DMR 桥接",
    "MMDVMModeYSF2NXDN" to "YSF → NXDN 桥接",
    "MMDVMModeYSF2P25" to "YSF → P25 桥接",
    "MMDVMModeDMR2YSF" to "DMR → YSF 桥接",
    "MMDVMModeDMR2NXDN" to "DMR → NXDN 桥接",
    "MMDVMModeCWID" to "CWID 呼号播报"
)

private val STEP_TITLES = listOf(
    "Wi-Fi 网络", "基本设置", "工作模式", "射频设置",
    "DMR 网络", "调制解调器", "屏幕与时区", "确认保存"
)

private enum class ExtraSection(val title: String) {
    NETWORK("网络设置"),
    MODEM("调制解调器"),
    DISPLAY("屏幕与时区"),
    DMR("DMR 网络")
}

/** 页面上同一 name 的一组控件（文本框/下拉框/单选组）。 */
private data class ExtraGroup(val name: String, val fields: List<ConfigureField>) {
    val first: ConfigureField get() = fields.first()
    val isCheckbox: Boolean get() = first.inputType == "checkbox"
    val isRadio: Boolean get() = first.inputType == "radio"
    val isSelect: Boolean get() = first.kind == ConfigureFieldKind.SELECT
    val isPassword: Boolean
        get() = first.inputType == "password" ||
            name.lowercase().let { it.contains("pass") || it.contains("psk") }
    val options: List<String>
        get() = when {
            isSelect -> first.options.map { it.value }.distinct()
            isRadio -> fields.map { it.value }.distinct()
            else -> emptyList()
        }

    /** 下拉框显示给用户的文字；DMR 服务器选项的 value 里带密码，不能直接显示。 */
    val optionTexts: Map<String, String>
        get() = if (isSelect) {
            first.options.associate { it.value to it.text.ifBlank { it.value } }
        } else {
            emptyMap()
        }

    /** 页面上的当前值；单选组取勾选的那个，下拉框取选中项。 */
    val defaultValue: String
        get() = when {
            isSelect -> first.value
            isRadio -> fields.firstOrNull { it.checked }?.value ?: first.value
            else -> first.value
        }
}

private val KNOWN_FIELD_NAMES: Set<String> =
    (listOf(FIELD_CALLSIGN, FIELD_DMR_ID, FIELD_TRX_MODE, FIELD_FREQ, FIELD_FREQ_RX, FIELD_FREQ_TX,
        FIELD_COLOR_CODE) + MODE_FIELDS.map { it.first })
        .map { it.lowercase() }
        .toSet()

private val TIMEZONE_KEYWORDS =
    listOf("timezone", "time_zone", "time zone", "ntp", "systemtime", "timeformat")
private val DISPLAY_KEYWORDS =
    listOf("display", "oled", "nextion", "lcd", "screen", "brightness", "rotation", "rotate", "language")
private val MODEM_KEYWORDS =
    listOf(
        "modem", "baud", "serial", "uart", "rxoffset", "txoffset", "protocol",
        "hardware", "confport", "speed"
    )
private val DMR_KEYWORDS =
    listOf("dmr", "bmhs", "bmextended", "systemx", "tgif", "custnet", "xlx")
private val NETWORK_KEYWORDS =
    listOf(
        "wifi", "ssid", "psk", "hostname", "hostame", "dhcp", "ipaddress", "ip_address",
        "gateway", "netmask", "dns"
    )

private fun sectionOf(name: String): ExtraSection? {
    val key = name.lowercase()
    // APRS 与 Auto AP 的字段不属于 Wi-Fi / DMR 配置，先排除，避免串到别的步骤里
    if (key.contains("aprs") || key.startsWith("autoap")) return null
    return when {
        TIMEZONE_KEYWORDS.any { key.contains(it) } -> ExtraSection.DISPLAY
        DISPLAY_KEYWORDS.any { key.contains(it) } -> ExtraSection.DISPLAY
        MODEM_KEYWORDS.any { key.contains(it) } -> ExtraSection.MODEM
        DMR_KEYWORDS.any { key.contains(it) } -> ExtraSection.DMR
        NETWORK_KEYWORDS.any { key.contains(it) } -> ExtraSection.NETWORK
        else -> null
    }
}

/** 从盒子网页表单里挑出该分组的字段，字段名不完全固定也能生效。 */
private fun extraGroups(form: ConfigureForm, section: ExtraSection): List<ExtraGroup> =
    form.fields
        .filter { it.kind != ConfigureFieldKind.HIDDEN }
        .filter { it.name.lowercase() !in KNOWN_FIELD_NAMES }
        .filter { sectionOf(it.name) == section }
        .groupBy { it.name.lowercase() }
        .values
        .map { ExtraGroup(it.first().name, it) }

private fun allExtraGroups(form: ConfigureForm): List<ExtraGroup> =
    ExtraSection.values().flatMap { extraGroups(form, it) }

private fun visibleFieldNames(form: ConfigureForm): List<String> =
    form.fields
        .filter { it.kind != ConfigureFieldKind.HIDDEN }
        .map { it.name }
        .distinct()

/** Wi-Fi 独立页面：把该页所有可见字段都交给用户填，不靠关键字猜。 */
private fun visibleGroups(form: ConfigureForm): List<ExtraGroup> =
    form.fields
        .filter { it.kind != ConfigureFieldKind.HIDDEN }
        .groupBy { it.name.lowercase() }
        .values
        .map { ExtraGroup(it.first().name, it) }

private fun collectWifiGroups(form: ConfigureForm, dedicated: Boolean): List<ExtraGroup> =
    if (dedicated) visibleGroups(form) else extraGroups(form, ExtraSection.NETWORK)

/**
 * 原生配置向导：模拟电脑浏览器登录 WPSD 后台，在 Configuration 页面填空，
 * 再整表提交（等同于手工点击 Apply Changes）。不直接改写配置文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigWizardScreen(
    device: MmdvmDevice,
    onBack: () -> Unit,
    onOpenWebConfig: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentials = remember(device.host) { CredentialStore.get(context, device.host) }
    val client = remember(device.host) { WpsdConfigurePageClient(device.host, credentials) }

    var status by remember { mutableStateOf(WizardStatus.Detecting) }
    var message by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<BoxInfo?>(null) }
    var form by remember { mutableStateOf<ConfigureForm?>(null) }
    var networkForm by remember { mutableStateOf<ConfigureForm?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var wifiSaving by remember { mutableStateOf(false) }
    var wifiSaved by remember { mutableStateOf(false) }
    var wifiError by remember { mutableStateOf<String?>(null) }
    var duplex by remember { mutableStateOf(false) }
    var showBackups by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<ConfigSnapshot>>(emptyList()) }
    var restoring by remember { mutableStateOf(false) }
    var boxBackupSupported by remember { mutableStateOf<Boolean?>(null) }
    var boxBusy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    val modes = remember { mutableStateMapOf<String, Boolean>() }
    val checks = remember { mutableStateMapOf<String, Boolean>() }
    val wifiSource = networkForm ?: form
    val wifiDedicated = networkForm != null && networkForm?.url != form?.url
    val wifiFieldGroups = wifiSource?.let { collectWifiGroups(it, wifiDedicated) }.orEmpty()

    fun load() {
        if (saving) return
        status = WizardStatus.Detecting
        message = null
        saveError = null
        scope.launch {
            try {
                val detected = client.detectSystem()
                info = detected
                when {
                    detected.system == BoxSystem.UNKNOWN -> {
                        status = WizardStatus.Error
                        message = "无法识别盒子系统。请确认手机与盒子在同一网络，" +
                            "并在设备详情里确认后台账号密码。"
                    }

                    !detected.wizardSupported -> {
                        status = WizardStatus.Unsupported
                        message = "检测到 ${detected.displayName}，当前版本的原生配置向导支持 WPSD 与 Pi-Star 系统。"
                    }

                    else -> {
                        status = WizardStatus.Loading
                        val opened = client.load()
                        form = opened
                        networkForm = runCatching { client.loadNetwork() }.getOrNull()
                        values.clear()
                        modes.clear()
                        checks.clear()
                        wifiSaved = false
                        wifiError = null
                        // Wi-Fi 页会把盒子当前保存的密码明文回显（Pi-Star），直接填进输入框方便核对
                        networkForm?.let { wifi ->
                            collectWifiGroups(wifi, wifi.url != opened.url).forEach { group ->
                                val current = group.first.value
                                if (group.isPassword && current.isNotBlank()) {
                                    values[group.name] = current
                                }
                            }
                        }
                        values[FIELD_CALLSIGN] = opened.valueOf(FIELD_CALLSIGN).orEmpty()
                        values[FIELD_DMR_ID] = opened.valueOf(FIELD_DMR_ID).orEmpty()
                        values[FIELD_COLOR_CODE] =
                            opened.selectValue(FIELD_COLOR_CODE).orEmpty().ifBlank { "0" }
                        duplex = opened.radioValue(FIELD_TRX_MODE).equals("DUPLEX", true)
                        val single = opened.valueOf(FIELD_FREQ).orEmpty()
                        val rx = opened.valueOf(FIELD_FREQ_RX)?.takeIf { it.isNotBlank() } ?: single
                        val tx = opened.valueOf(FIELD_FREQ_TX)?.takeIf { it.isNotBlank() } ?: single
                        values[FIELD_FREQ] = formatMhz(single.ifBlank { rx })
                        values[FIELD_FREQ_RX] = formatMhz(rx)
                        values[FIELD_FREQ_TX] = formatMhz(tx)
                        MODE_FIELDS.forEach { (field, _) ->
                            if (opened.has(field)) modes[field] = opened.isChecked(field)
                        }
                        step = 0
                        status = WizardStatus.Ready
                        // 进入向导时先把“未修改的原始配置”存一份，改错了可以一键还原
                        runCatching {
                            ConfigBackupStore.saveIfChanged(
                                context = context,
                                host = device.host,
                                snapshot = ConfigBackupStore.capture(opened, "修改前自动备份")
                            )
                        }
                    }
                }
            } catch (e: ConfigurePageException) {
                status = WizardStatus.Error
                message = e.message
            } catch (e: Exception) {
                status = WizardStatus.Error
                message = describeFailure(e, device.host)
            }
        }
    }

    fun save() {
        val opened = form ?: return
        val problem = validateValues(opened, values, duplex)
        if (problem != null) {
            saveError = problem
            return
        }
        saving = true
        saveError = null
        // 不等待盒子回应：请求发出后立即提示完成，盒子在后台自行重建配置并重启
        val edits = buildEdits(opened, values, duplex, modes, checks, includeNetwork = false)
        scope.launch {
            runCatching { client.apply(opened, edits) }
        }
        status = WizardStatus.Saved
        saving = false
    }

    /** 第一步单独保存 Wi-Fi，之后重启盒子让它连上无线路由器，再回来配其它参数。 */
    fun saveWifi() {
        val source = wifiSource ?: return
        if (wifiFieldGroups.isEmpty()) return
        validateWifi(source, wifiFieldGroups, values)?.let { problem ->
            wifiError = problem
            wifiSaved = false
            return
        }
        wifiSaving = true
        wifiError = null
        // 不等待盒子回应：请求发出后立即提示，盒子会在后台自行重建配置、重启网络
        val edits = buildNetworkEdits(wifiFieldGroups, values, checks)
        scope.launch {
            runCatching { client.applyNetwork(source, edits) }
        }
        wifiSaved = true
        wifiSaving = false
    }

    /** 用快照整表重提交，把盒子恢复成备份时的配置。 */
    fun restoreSnapshot(snapshot: ConfigSnapshot) {
        val opened = form ?: return
        restoring = true
        scope.launch {
            val result = runCatching { client.apply(opened, snapshot.toEdits()) }
            restoring = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "已提交还原（${snapshot.timeText()}），盒子应用后会重建配置" },
                    onFailure = { "还原失败：${it.message ?: it.javaClass.simpleName}" }
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 下载盒子整机配置备份（WPSD 的 config_backup.php），存到 App 的 backups 目录。 */
    fun downloadBoxBackup() {
        boxBusy = true
        scope.launch {
            runCatching { client.downloadConfigBackup() }.fold(
                onSuccess = { (name, bytes) ->
                    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
                        .apply { mkdirs() }
                    val file = File(dir, name)
                    val written = runCatching { file.writeBytes(bytes) }.isSuccess
                    Toast.makeText(
                        context,
                        if (written) "已保存备份：${file.name}" else "写入备份文件失败",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = {
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            )
            boxBusy = false
        }
    }

    /** 把选中的 zip 上传到盒子还原。 */
    fun restoreBoxBackup(name: String, bytes: ByteArray) {
        boxBusy = true
        scope.launch {
            runCatching { client.restoreConfigBackup(name, bytes) }.fold(
                onSuccess = {
                    Toast.makeText(
                        context,
                        "已上传还原，盒子正在恢复配置并重启服务",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = {
                    Toast.makeText(context, "还原失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            )
            boxBusy = false
        }
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        boxBusy = true
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
                ?: "WPSD_Config_backup.zip"
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            boxBusy = false
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
            } else {
                pendingRestore = name to bytes
            }
        }
    }

    LaunchedEffect(device.host) { load() }
    BackHandler(enabled = true, onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("配置向导")
                        Text(
                            text = device.host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回设备详情"
                        )
                    }
                },
                actions = {
                    if (status == WizardStatus.Ready) {
                        TextButton(onClick = {
                            backups = ConfigBackupStore.list(context, device.host)
                            boxBackupSupported = null
                            showBackups = true
                            scope.launch {
                                boxBackupSupported = runCatching { client.configBackupSupported() }
                                    .getOrDefault(false)
                            }
                        }) {
                            Text("备份/还原")
                        }
                        IconButton(onClick = { load() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "重新读取配置"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            when (status) {
                WizardStatus.Detecting, WizardStatus.Loading -> LoadingView(
                    text = if (status == WizardStatus.Detecting) {
                        "正在检测盒子系统…"
                    } else {
                        "正在打开盒子网页配置页…"
                    }
                )

                WizardStatus.Unsupported -> MessageView(
                    title = "暂未适配该系统",
                    text = message.orEmpty(),
                    onOpenWebConfig = onOpenWebConfig,
                    onBack = onBack
                )

                WizardStatus.Error -> MessageView(
                    title = "无法使用配置向导",
                    text = message ?: "未知错误",
                    onOpenWebConfig = onOpenWebConfig,
                    onBack = onBack,
                    onRetry = { load() }
                )

                WizardStatus.Saved -> SavedView(
                    onBack = onBack,
                    onOpenWebConfig = onOpenWebConfig
                )

                WizardStatus.Ready -> {
                    val opened = form
                    if (opened == null) {
                        LoadingView(text = "正在打开盒子网页配置页…")
                    } else {
                        InfoCard(info = info)
                        StepHeader(step = step)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (step) {
                                0 -> WifiStep(
                                    groups = wifiFieldGroups,
                                    values = values,
                                    checks = checks,
                                    saving = wifiSaving,
                                    saved = wifiSaved,
                                    error = wifiError,
                                    // WPSD 的 WiFi Manager 是“新增并连接”，SSID 和密码都必须填
                                    requirePassword = wifiSource?.let { source ->
                                        source.has("passphrase") &&
                                            source.valueOf("action").equals("add", true)
                                    } == true,
                                    onSave = { saveWifi() },
                                    onBoxScan = wifiSource?.let { source ->
                                        { client.scanWifi(source) }
                                    }
                                )

                                1 -> BasicStep(opened, values, duplex) { duplex = it }
                                2 -> ModeStep(opened, values, modes)
                                3 -> RadioStep(opened, values, duplex)
                                4 -> ExtraStep(
                                    section = ExtraSection.DMR,
                                    groups = extraGroups(opened, ExtraSection.DMR),
                                    values = values,
                                    checks = checks,
                                    availableFields = visibleFieldNames(opened)
                                )

                                5 -> ExtraStep(
                                    section = ExtraSection.MODEM,
                                    groups = extraGroups(opened, ExtraSection.MODEM),
                                    values = values,
                                    checks = checks,
                                    availableFields = visibleFieldNames(opened)
                                )

                                6 -> ExtraStep(
                                    section = ExtraSection.DISPLAY,
                                    groups = extraGroups(opened, ExtraSection.DISPLAY),
                                    values = values,
                                    checks = checks,
                                    availableFields = visibleFieldNames(opened)
                                )

                                else -> ReviewStep(
                                    opened, values, duplex, modes, checks, wifiSaved
                                )
                            }
                            saveError?.let { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { if (step > 0) step-- },
                                enabled = step > 0 && !saving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("上一步")
                            }
                            Button(
                                onClick = { if (step < STEP_TITLES.lastIndex) step++ else save() },
                                enabled = !saving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    when {
                                        saving -> "写入中…"
                                        step < STEP_TITLES.lastIndex -> "下一步"
                                        else -> "保存到盒子"
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    if (showBackups) {
        AlertDialog(
            onDismissRequest = { if (!restoring) showBackups = false },
            title = { Text("配置备份 / 还原") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "进入向导时会自动保存一份修改前的配置（每台盒子最多 6 份）。" +
                            "还原会用快照整表重新提交，等同在网页后台再点一次 Apply Changes。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (boxBackupSupported) {
                        null -> Text(
                            text = "正在检查盒子的整机备份页…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        true -> {
                            Text(
                                text = "盒子支持整机配置备份（config_backup.php）：可下载 .zip 存到手机，" +
                                    "也可以选一个 .zip 上传还原。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = !boxBusy && !restoring,
                                    onClick = { downloadBoxBackup() }
                                ) {
                                    Text("下载盒子备份")
                                }
                                OutlinedButton(
                                    enabled = !boxBusy && !restoring,
                                    onClick = {
                                        backupPicker.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/octet-stream",
                                                "*/*"
                                            )
                                        )
                                    }
                                ) {
                                    Text("上传还原")
                                }
                            }
                        }

                        else -> Unit
                    }
                    if (boxBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (backups.isEmpty()) {
                        Text("还没有备份。", style = MaterialTheme.typography.bodySmall)
                    }
                    backups.forEach { snapshot ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(snapshot.timeText(), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = snapshot.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                enabled = !restoring && form != null,
                                onClick = { restoreSnapshot(snapshot) }
                            ) {
                                Text("还原")
                            }
                            TextButton(
                                enabled = !restoring,
                                onClick = {
                                    ConfigBackupStore.remove(context, device.host, snapshot.time)
                                    backups = ConfigBackupStore.list(context, device.host)
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                    if (restoring) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !restoring && form != null,
                    onClick = {
                        val opened = form ?: return@TextButton
                        ConfigBackupStore.save(
                            context = context,
                            host = device.host,
                            snapshot = ConfigBackupStore.capture(opened, "手动备份")
                        )
                        backups = ConfigBackupStore.list(context, device.host)
                        Toast.makeText(context, "已保存当前配置快照", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("立即备份")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackups = false }) {
                    Text("关闭")
                }
            }
        )
    }

    pendingRestore?.let { (name, bytes) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("用备份还原盒子") },
            text = {
                Text(
                    "将把 $name 上传到盒子并覆盖当前配置，盒子会重启相关服务。" +
                        "如果还没下载当前配置的备份，建议先取消并下载一份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    restoreBoxBackup(name, bytes)
                }) {
                    Text("确认还原")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun LoadingView(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessageView(
    title: String,
    text: String,
    onOpenWebConfig: () -> Unit,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("重新检测")
            }
        }
        OutlinedButton(onClick = onOpenWebConfig, modifier = Modifier.fillMaxWidth()) {
            Text("打开网页配置")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回设备详情")
        }
    }
}

@Composable
private fun SavedView(onBack: () -> Unit, onOpenWebConfig: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("配置已提交，盒子正在重启服务", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "已按网页 Configuration 页的配置提交，盒子会在后台重建文件并重启服务，" +
                "约需 10-60 秒。不用等待，稍后重新刷新设备即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回设备详情")
        }
        OutlinedButton(onClick = onOpenWebConfig, modifier = Modifier.fillMaxWidth()) {
            Text("打开网页配置")
        }
    }
}

@Composable
private fun InfoCard(info: BoxInfo?) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = info?.displayName ?: "WPSD",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "提交方式：模拟网页 Configuration 页填空 + Apply Changes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepHeader(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "步骤 ${step + 1} / ${STEP_TITLES.size} · ${STEP_TITLES[step]}",
            style = MaterialTheme.typography.titleSmall
        )
        LinearProgressIndicator(
            progress = { (step + 1).toFloat() / STEP_TITLES.size },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BasicStep(
    form: ConfigureForm,
    values: MutableMap<String, String>,
    duplex: Boolean,
    onDuplexChange: (Boolean) -> Unit
) {
    TextFieldField(
        label = "呼号 Callsign",
        hint = "例：BH2VLF",
        value = values[FIELD_CALLSIGN].orEmpty(),
        enabled = true,
        keyboardType = KeyboardType.Text
    ) { values[FIELD_CALLSIGN] = it.uppercase(Locale.US) }

    TextFieldField(
        label = "DMR ID",
        hint = "例：4601234",
        value = values[FIELD_DMR_ID].orEmpty(),
        enabled = form.has(FIELD_DMR_ID),
        keyboardType = KeyboardType.Number
    ) { values[FIELD_DMR_ID] = it }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("双工模式 Duplex", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "关闭为单工 Simplex，开启为双工 Duplex",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = duplex, onCheckedChange = onDuplexChange)
    }
}

@Composable
private fun RadioStep(
    form: ConfigureForm,
    values: MutableMap<String, String>,
    duplex: Boolean
) {
    if (duplex) {
        TextFieldField(
            label = "接收频率 RX（MHz）",
            hint = "例：438.800",
            value = values[FIELD_FREQ_RX].orEmpty(),
            enabled = true,
            keyboardType = KeyboardType.Decimal
        ) { values[FIELD_FREQ_RX] = it }

        TextFieldField(
            label = "发射频率 TX（MHz）",
            hint = "例：438.800",
            value = values[FIELD_FREQ_TX].orEmpty(),
            enabled = true,
            keyboardType = KeyboardType.Decimal
        ) { values[FIELD_FREQ_TX] = it }

        if (!form.has(FIELD_FREQ_RX) && !form.has(FIELD_FREQ_TX)) {
            Text(
                text = "盒子网页当前未显示双工频率输入框（通常是因为还没有启用任何工作模式）。" +
                    "这里填写后保存，会按 WPSD 的字段名一并提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        TextFieldField(
            label = "频率（MHz）",
            hint = "例：438.800",
            value = values[FIELD_FREQ].orEmpty(),
            enabled = true,
            keyboardType = KeyboardType.Decimal
        ) { values[FIELD_FREQ] = it }

        if (!form.has(FIELD_FREQ) && !form.has(FIELD_FREQ_RX)) {
            Text(
                text = "配置页没有单独的“频率”字段，保存时会写入接收频率并同步发射频率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeStep(
    form: ConfigureForm,
    values: MutableMap<String, String>,
    modes: MutableMap<String, Boolean>
) {
    MODE_FIELDS.forEach { (field, label) ->
        if (!form.has(field)) {
            Text(
                text = "当前固件未提供：$label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = modes[field] == true,
                    onCheckedChange = { modes[field] = it }
                )
            }
        }
    }
}

@Composable
private fun TextFieldField(
    label: String,
    hint: String,
    value: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    password: Boolean = false,
    onChange: (String) -> Unit
) {
    if (!enabled) {
        Text(
            text = "当前固件未提供：$label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (password) {
            {
                Text(
                    text = if (visible) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { visible = !visible }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 网络 / 调制解调器 / 屏幕与时区这些分组，字段按盒子网页实际提供的来渲染。 */
@Composable
private fun ExtraStep(
    section: ExtraSection,
    groups: List<ExtraGroup>,
    values: MutableMap<String, String>,
    checks: MutableMap<String, Boolean>,
    availableFields: List<String> = emptyList()
) {
    if (groups.isEmpty()) {
        Text(
            text = when (section) {
                ExtraSection.NETWORK ->
                    "没有找到可回放的 Wi-Fi 表单（已尝试 /admin/wifi.php、/admin/wifi-manager.php、" +
                        "/admin/network.php、/admin/wlan.php 等，并检查了配置页里的网络链接）。" +
                        "Pi-Star 4.1.5 的 Wi-Fi 页面由脚本动态生成、没有表单，需要在电脑网页后台设置。"

                ExtraSection.MODEM ->
                    "配置页里没有找到调制解调器设置项。WPSD 通常会自己识别调制解调器型号，" +
                        "如果你的网页后台确实有“调制解调器/Modem Type”下拉框，把下面的字段名告诉我，我加上。"

                ExtraSection.DISPLAY ->
                    "这个系统的配置页里没有找到屏幕或时区设置项，可先跳过这一步。"

                ExtraSection.DMR ->
                    "配置页里没有找到 DMR 服务器/网关设置项。Pi-Star 的 DMR 主机在“专家编辑器 →" +
                        " DMRGateway”里，不在配置页，可先跳过这一步。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (availableFields.isNotEmpty()) {
            Text(
                text = "配置页已读取到的字段（${availableFields.size} 个）：" +
                    availableFields.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (section == ExtraSection.MODEM) {
        Text(
            text = "本步从网页读取到 ${groups.size} 个字段：" +
                groups.joinToString("、") { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (section == ExtraSection.DMR) {
        Text(
            text = "General DMR Settings",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "连 BrandMeister 只需：呼号、DMR ID（在“基本设置”里）、DMR 主机（选 BM_xxx）、" +
                "ESSID（一般 None）、色码。下面按这个顺序排列，其余网关参数不改也能通联。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Pi-Star 4.1.5 的配置页没有色码字段（色码在专家编辑器里），这时不显示
        if (availableFields.any { it.equals(FIELD_COLOR_CODE, ignoreCase = true) }) {
            TextFieldField(
                label = "DMR 色码 Color Code",
                hint = "0-15",
                value = values[FIELD_COLOR_CODE].orEmpty(),
                enabled = true,
                keyboardType = KeyboardType.Number
            ) { values[FIELD_COLOR_CODE] = it }
        }
    }

    if (section == ExtraSection.NETWORK) {
        Text(
            text = "Wi-Fi 位于独立页面，保存时会单独提交到该页面；密码留空表示不修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ExtraFields(if (section == ExtraSection.DMR) dmrSorted(groups) else groups, values, checks)
}

/** 连接 BrandMeister 必需的字段排在最前面，其余网关参数保持网页原顺序。 */
private val DMR_PRIORITY = listOf(
    "dmrmasterhost",
    "bmextendedid",
    "dmrplusextendedid",
    "bmhssecurity",
    "dmrprimary",
    "dmrgatewaynet1en"
)

private fun dmrSorted(groups: List<ExtraGroup>): List<ExtraGroup> =
    groups.sortedBy { group ->
        val index = DMR_PRIORITY.indexOf(group.name.lowercase())
        if (index >= 0) index else DMR_PRIORITY.size
    }

/** 把“连不上盒子”这类底层异常翻译成能照做的提示。 */
private fun describeFailure(e: Exception, host: String): String {
    val text = e.message.orEmpty()
    val unreachable = e is java.io.IOException && (
        text.contains("failed to connect", ignoreCase = true) ||
            text.contains("Connection refused", ignoreCase = true) ||
            text.contains("No route to host", ignoreCase = true)
        )
    val timedOut = e is java.io.IOException &&
        (text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true))
    return when {
        timedOut ->
            "盒子响应超时（$host）。盒子重建配置、重启服务可能要几十秒：" +
                "请等半分钟后重新打开向导确认是否已生效；若一直超时，检查手机与盒子是否在同一网络。"

        unreachable ->
            "连不上盒子（$host）。手机当前可能连在家里的 Wi-Fi 或移动网络上，" +
                "请先把手机 Wi-Fi 连到盒子自己的热点、和盒子处于同一网段，再重试；" +
                "如果盒子已经接入家里路由器，请把设备地址改成盒子的新 IP。"

        else -> "提交失败：${e.message ?: e.javaClass.simpleName}"
    }
}

/** 读取手机当前连接的 Wi-Fi 名称；密码系统不允许读取，只能手动输入。 */
@SuppressLint("MissingPermission")
private fun currentWifiSsid(wifiManager: WifiManager?): String? {
    val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull() ?: return null
    val ssid = raw.removeSurrounding("\"")
    return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
}

@SuppressLint("MissingPermission")
@Composable
private fun WifiStep(
    groups: List<ExtraGroup>,
    values: MutableMap<String, String>,
    checks: MutableMap<String, Boolean>,
    saving: Boolean,
    saved: Boolean,
    error: String?,
    requirePassword: Boolean,
    onSave: () -> Unit,
    onBoxScan: (suspend () -> List<String>)?
) {
    val context = LocalContext.current
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    val ssidGroup = groups.firstOrNull { it.name.contains("ssid", true) }
    val currentSsid = remember { currentWifiSsid(wifiManager) }
    var scanned by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var boxScanning by remember { mutableStateOf(false) }
    var boxScanned by remember { mutableStateOf<List<String>>(emptyList()) }
    var boxScanHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 手机当下连的 Wi-Fi 只在页面和输入框都为空时填入，避免覆盖盒子里已保存的 SSID
    LaunchedEffect(ssidGroup?.name, currentSsid) {
        val field = ssidGroup?.name ?: return@LaunchedEffect
        if (!currentSsid.isNullOrBlank() &&
            values[field].isNullOrBlank() &&
            ssidGroup.first.value.isBlank()
        ) {
            values[field] = currentSsid
        }
    }

    fun startScan() {
        if (wifiManager == null) {
            scanHint = "这台手机没有可用的 Wi-Fi 模块。"
            return
        }
        scanning = true
        scanHint = null
        val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (!started) {
            scanning = false
            scanHint = "系统拒绝了扫描请求：请打开手机的“定位服务”，并允许本应用使用位置信息（Android 8 起扫描 Wi-Fi 必须有定位）。"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScan() else scanHint = "没有位置权限就无法扫描附近的 Wi-Fi，可手动输入 SSID。"
    }

    DisposableEffect(wifiManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val results = runCatching { wifiManager?.scanResults }.getOrNull().orEmpty()
                scanned = results
                    .filter { it.SSID.isNotBlank() }
                    .map { it.SSID to it.level }
                    .distinctBy { it.first }
                    .sortedByDescending { it.second }
                scanning = false
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.applicationContext.unregisterReceiver(receiver) } }
    }

    Text(
        text = "先设置 Wi-Fi：提交时手机要连着盒子自己的热点（或与盒子同一网络），中途别切到家里 Wi-Fi。" +
            "保存后断电重启盒子，等它连上无线路由器，手机也连到同一路由器，再点“下一步”继续设置其余参数。" +
            "如果盒子已经联网，可直接点下一步。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (groups.isEmpty()) {
        Text(
            text = "这个系统没有找到可填写的 Wi-Fi 表单" +
                "（已尝试 /admin/wifi-manager.php、/admin/wifi.php、/admin/network.php 等，" +
                "并检查了配置页里的网络链接）。可先跳过，或把 Wi-Fi 页面里的字段名告诉我。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            text = if (requirePassword) {
                "WPSD 会在盒子里新建一个 Wi-Fi 连接并立即连上，SSID 和密码都要填写（密码 8–63 位）。"
            } else {
                "Wi-Fi 密码留空表示不修改。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExtraFields(
            groups, values, checks,
            passwordHint = if (requirePassword) "必填，8–63 位" else "留空表示不修改"
        )
        if (ssidGroup != null) {
            if (!currentSsid.isNullOrBlank()) {
                Text(
                    text = "手机当前连接：$currentSsid（SSID 已自动填入；系统不允许读取 Wi-Fi 密码，请手动输入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) startScan()
                    else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (scanning) "正在扫描…" else "扫描附近 Wi-Fi")
            }
            scanHint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (scanned.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "点一下把该 Wi-Fi 名称填入 SSID：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        scanned.take(20).forEach { (ssid, level) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { values[ssidGroup.name] = ssid }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ssid, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "$level dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (onBoxScan != null) {
                OutlinedButton(
                    onClick = {
                        if (boxScanning) return@OutlinedButton
                        boxScanning = true
                        boxScanHint = null
                        scope.launch {
                            val result = runCatching { onBoxScan() }.getOrElse { emptyList() }
                            boxScanned = result
                            boxScanning = false
                            if (result.isEmpty()) {
                                boxScanHint = "盒子没有返回扫描结果：这个系统的 Wi-Fi 页可能不支持扫描，" +
                                    "可以点上面的手机扫描，或手动输入 SSID。"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (boxScanning) "盒子正在扫描…" else "让盒子扫描周边 Wi-Fi（Pi-Star）")
                }
                boxScanHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (boxScanned.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "盒子扫描到以下 Wi-Fi，点一下填入 SSID：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            boxScanned.take(20).forEach { ssid ->
                                Text(
                                    text = ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { values[ssidGroup.name] = ssid }
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "正在提交 Wi-Fi…" else "保存 Wi-Fi")
        }
        if (saved) {
            Text(
                text = "Wi-Fi 已提交。请断电重启盒子，约 1 分钟后它会连接你填入的无线路由器；" +
                    "手机连到同一路由器后继续下一步（如果设备是手工填 IP 添加的，新 IP 可能需要更新）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ExtraFields(
    groups: List<ExtraGroup>,
    values: MutableMap<String, String>,
    checks: MutableMap<String, Boolean>,
    passwordHint: String = "留空表示不修改"
) {
    groups.forEach { group ->
        val name = group.name
        val label = extraLabel(name)
        when {
            group.isCheckbox -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = checks[name] ?: group.first.checked,
                    onCheckedChange = { checks[name] = it }
                )
            }

            group.options.isNotEmpty() -> SelectField(
                label = label,
                options = group.options,
                selected = values[name] ?: group.defaultValue,
                labels = group.optionTexts
            ) { values[name] = it }

            group.isPassword -> TextFieldField(
                label = label,
                hint = passwordHint,
                value = values[name].orEmpty(),
                enabled = true,
                keyboardType = KeyboardType.Password,
                password = true
            ) { values[name] = it }

            else -> TextFieldField(
                label = label,
                hint = group.first.value.takeIf { it.isNotBlank() } ?: "",
                value = values[name] ?: group.first.value,
                enabled = true,
                keyboardType = KeyboardType.Text
            ) { values[name] = it }
        }
    }
}

@Composable
private fun SelectField(
    label: String,
    options: List<String>,
    selected: String,
    labels: Map<String, String> = emptyMap(),
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 选项太多（如 WPSD 的 DMR 主机有 300 多项）时提供搜索，否则下拉没法翻
    val searchable = options.size > 30
    val shown = remember(options, query) {
        if (!searchable || query.isBlank()) {
            options
        } else {
            val key = query.trim().lowercase()
            options.filter { option ->
                (labels[option] ?: option).lowercase().contains(key) ||
                    option.lowercase().contains(key)
            }
        }
    }
    Box {
        OutlinedTextField(
            value = labels[selected] ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        // 输入框会自己吃掉点击事件，这里叠一层透明可点区域来弹出菜单
        Box(
            Modifier
                .matchParentSize()
                .clickable {
                    query = ""
                    expanded = true
                }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            }
        ) {
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索（共 ${options.size} 项）") },
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
            if (shown.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("没有匹配的选项") },
                    onClick = {}
                )
            }
            shown.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels[option] ?: option.ifBlank { "（空）" }) },
                    onClick = {
                        expanded = false
                        query = ""
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private val EXTRA_LABELS = mapOf(
    "dmrmasterhost" to "DMR 主机（服务器）",
    "confwifissid" to "Wi-Fi 名称 SSID",
    "wifissid" to "Wi-Fi 名称 SSID",
    "ssid" to "Wi-Fi 名称 SSID",
    "confwifipass" to "Wi-Fi 密码",
    "wifipass" to "Wi-Fi 密码",
    "wifipsk" to "Wi-Fi 密码",
    "confhostname" to "主机名 Hostname",
    "hostname" to "主机名 Hostname",
    "modemtype" to "调制解调器类型",
    "modem" to "调制解调器",
    "modembaud" to "串口波特率",
    "modembaudrate" to "串口波特率",
    "baud" to "串口波特率",
    "modemport" to "串口设备",
    "displaytype" to "屏幕类型",
    "displayport" to "屏幕端口",
    "displaybrightness" to "屏幕亮度",
    "displayrotation" to "屏幕旋转",
    "timezone" to "时区 TimeZone",
    "time_zone" to "时区 TimeZone",
    "systemtimezone" to "系统时区",
    "systemtimeformat" to "时间格式（12/24 小时）",
    "dashboardlanguage" to "后台语言",
    "confhardware" to "调制解调器型号 Modem Type",
    "confport" to "调制解调器端口",
    "confhardwarespeed" to "调制解调器波特率",
    "dmrmasterhost1" to "DMR 服务器 1（BrandMeister）",
    "dmrmasterhost2" to "DMR 服务器 2（DMR+）",
    "dmrmasterhost3" to "DMR 服务器 3（XLX）",
    "dmrmasterhost4" to "DMR 服务器 4（TGIF）",
    "dmrmasterhost5" to "DMR 服务器 5（SystemX）",
    "dmrmasterhost3startupmodule" to "XLX 启动模块",
    "xlxtimeslot" to "XLX 时隙",
    "dmrprimary" to "首选 DMR 网络",
    "dmrgatewaynet1en" to "BrandMeister 网络启用",
    "dmrgatewaynet2en" to "DMR+ 网络启用",
    "dmrgatewaynet3en" to "XLX 网络启用",
    "dmrgatewaynet4en" to "TGIF 网络启用",
    "dmrgatewaynet5en" to "SystemX 网络启用",
    "dmrgatewayxlxen" to "XLX 网关启用",
    "dmrembeddedlconly" to "仅嵌入 LC（EmbeddedLCOnly）",
    "dmrdumptadata" to "输出 TA 数据（DumpTAData）",
    "dmrbeacon" to "DMR 信标（Beacon）",
    "dmrrfhangtime" to "RF 挂起时间（ms）",
    "dmrnethangtime" to "网络挂起时间（ms）",
    "bmextendedid" to "BrandMeister ESSID（扩展 ID）",
    "dmrplusextendedid" to "DMR+ ESSID（扩展 ID）",
    "custnetextendedid" to "自定义网络 ESSID（扩展 ID）",
    "systemxextendedid" to "SystemX ESSID（扩展 ID）",
    "tgifextendedid" to "TGIF ESSID（扩展 ID）",
    "xtgifextendedid" to "TGIF ESSID（扩展 ID）",
    "custnetenable" to "自定义网络启用",
    "custnetname" to "自定义网络名称",
    "custnetaddress" to "自定义网络地址",
    "custnetport" to "自定义网络端口",
    "custnetsecurity" to "自定义网络密码",
    "custnetoptions" to "自定义网络参数",
    "custnettglistfile" to "自定义网络 Talkgroup 列表文件",
    "custnetautorewrites" to "自定义网络自动重写",
    "confdmrwhitelist" to "DMR 白名单 ID（逗号分隔）",
    "bmhssecurity" to "BrandMeister 密码（HS）",
    "tgifhssecurity" to "TGIF 密码（HS）",
    "dmrnetworkoptions" to "DMR 网络参数",
    "dmrnetworkoptions5" to "SystemX 网络参数",
    "nodemode" to "节点模式",
    "confhostame" to "主机名 Hostname",
    "wificountrycode" to "Wi-Fi 国家代码（Country Code）",
    "passphrase" to "Wi-Fi 密码",
    "nodetype" to "节点类型",
    "ntpserver" to "NTP 服务器"
)

private fun extraLabel(name: String): String {
    EXTRA_LABELS[name.lowercase()]?.let { return it }
    Regex("^(ssid|psk)(\\d+)$", RegexOption.IGNORE_CASE).find(name)?.let { match ->
        val base = if (match.groupValues[1].equals("ssid", true)) "Wi-Fi 名称 SSID" else "Wi-Fi 密码"
        return "$base（第 ${match.groupValues[2].toIntOrNull()?.plus(1) ?: match.groupValues[2]} 个网络）"
    }
    val spaced = name.replace('_', ' ').replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    return spaced.split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}

@Composable
private fun ReviewStep(
    form: ConfigureForm,
    values: Map<String, String>,
    duplex: Boolean,
    modes: Map<String, Boolean>,
    checks: Map<String, Boolean>,
    wifiSaved: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReviewRow("Wi-Fi", if (wifiSaved) "已在第 1 步保存" else "未保存（可在第 1 步保存）")
        ReviewRow("呼号", values[FIELD_CALLSIGN].orEmpty())
        if (values[FIELD_DMR_ID].orEmpty().isNotBlank()) {
            ReviewRow("DMR ID", values[FIELD_DMR_ID].orEmpty())
        }
        ReviewRow("双工模式", if (duplex) "双工 Duplex" else "单工 Simplex")
        if (duplex) {
            if (values[FIELD_FREQ_RX].orEmpty().isNotBlank()) {
                ReviewRow("接收频率", "${values[FIELD_FREQ_RX]} MHz")
            }
            if (values[FIELD_FREQ_TX].orEmpty().isNotBlank()) {
                ReviewRow("发射频率", "${values[FIELD_FREQ_TX]} MHz")
            }
        } else if (values[FIELD_FREQ].orEmpty().isNotBlank()) {
            ReviewRow("频率", "${values[FIELD_FREQ]} MHz")
        }
        if (form.has(FIELD_COLOR_CODE) && values[FIELD_COLOR_CODE].orEmpty().isNotBlank()) {
            ReviewRow("DMR 色码", values[FIELD_COLOR_CODE].orEmpty())
        }
        MODE_FIELDS.forEach { (field, label) ->
            if (form.has(field)) {
                ReviewRow(label, if (modes[field] == true) "开" else "关")
            }
        }
        val extras = allExtraGroups(form).filter { sectionOf(it.name) != ExtraSection.NETWORK }
        extras.forEach { group ->
            val name = group.name
            val text = when {
                group.isCheckbox -> if (checks[name] ?: group.first.checked) "开" else "关"
                group.isPassword -> if (values[name].orEmpty().isBlank()) "（保持原值）" else "（已填写）"
                else -> values[name] ?: group.defaultValue
            }
            ReviewRow(extraLabel(name), text)
        }
        Text(
            text = "保存时会整表提交 Configuration 页面，效果等同于在电脑网页上填好后点击 Apply Changes，" +
                "由盒子重建全部配置文件；之后网页与本向导的值保持一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value.ifBlank { "（空）" }, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatMhz(raw: String): String {
    val cleaned = raw.trim().filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
    if (cleaned.isEmpty()) return ""
    // 盒子网页里出现过 449.900.000 这种多段格式，只保留第一个小数点
    val firstDot = cleaned.indexOf('.')
    val normalized = if (firstDot < 0) cleaned
    else cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
    val value = normalized.toDoubleOrNull() ?: return raw.trim()
    val truncated = kotlin.math.floor(value * 1000.0 + 1e-6) / 1000.0
    return String.format(Locale.US, "%.3f", truncated)
}

private fun buildEdits(
    form: ConfigureForm,
    values: Map<String, String>,
    duplex: Boolean,
    modes: Map<String, Boolean>,
    checks: Map<String, Boolean>,
    includeNetwork: Boolean
): ConfigureEdits {
    val outValues = LinkedHashMap<String, String>()
    fun put(name: String, value: String) {
        if (value.isNotBlank()) outValues[name] = value
    }

    put(FIELD_CALLSIGN, values[FIELD_CALLSIGN].orEmpty().trim().uppercase(Locale.US))
    put(FIELD_DMR_ID, values[FIELD_DMR_ID].orEmpty().trim())
    put(FIELD_COLOR_CODE, values[FIELD_COLOR_CODE].orEmpty().trim())

    if (duplex) {
        put(FIELD_FREQ_RX, values[FIELD_FREQ_RX].orEmpty().trim())
        put(FIELD_FREQ_TX, values[FIELD_FREQ_TX].orEmpty().trim())
    } else {
        val freq = values[FIELD_FREQ].orEmpty().trim()
        put(FIELD_FREQ, freq)
        put(FIELD_FREQ_RX, freq)
        put(FIELD_FREQ_TX, freq)
    }

    val radios = linkedMapOf(FIELD_TRX_MODE to if (duplex) "DUPLEX" else "SIMPLEX")
    val checkboxes = LinkedHashMap<String, Boolean>()
    MODE_FIELDS.forEach { (field, _) ->
        if (form.has(field)) checkboxes[field] = modes[field] == true
    }

    putExtraEdits(
        allExtraGroups(form).filter {
            includeNetwork || sectionOf(it.name) != ExtraSection.NETWORK
        },
        values, checks, outValues, radios, checkboxes
    )

    return ConfigureEdits(values = outValues, radioValues = radios, checkboxes = checkboxes)
}

/** Wi-Fi 独立页面只提交网络分组，避免把该页其它字段一起改写。 */
private fun buildNetworkEdits(
    groups: List<ExtraGroup>,
    values: Map<String, String>,
    checks: Map<String, Boolean>
): ConfigureEdits {
    val outValues = LinkedHashMap<String, String>()
    val radios = LinkedHashMap<String, String>()
    val checkboxes = LinkedHashMap<String, Boolean>()
    putExtraEdits(groups, values, checks, outValues, radios, checkboxes)
    return ConfigureEdits(values = outValues, radioValues = radios, checkboxes = checkboxes)
}

private fun putExtraEdits(
    groups: List<ExtraGroup>,
    values: Map<String, String>,
    checks: Map<String, Boolean>,
    outValues: MutableMap<String, String>,
    radios: MutableMap<String, String>,
    checkboxes: MutableMap<String, Boolean>
) {
    groups.forEach { group ->
        val name = group.name
        when {
            group.isCheckbox -> checkboxes[name] = checks[name] ?: group.first.checked
            group.isRadio -> radios[name] = values[name] ?: group.defaultValue
            group.options.isNotEmpty() -> outValues[name] = values[name] ?: group.defaultValue
            group.isPassword -> values[name]?.takeIf { it.isNotBlank() }?.let { outValues[name] = it }
            else -> outValues[name] = values[name] ?: group.first.value
        }
    }
}

/** WPSD 的 WiFi Manager 是“新增并连接”：SSID 和 8–63 位密码都必须填。 */
private fun validateWifi(
    form: ConfigureForm,
    groups: List<ExtraGroup>,
    values: Map<String, String>
): String? {
    val isAdd = form.has("passphrase") && form.valueOf("action").equals("add", true)
    if (!isAdd) return null
    val ssidGroup = groups.firstOrNull { it.name.contains("ssid", true) }
    val pskGroup = groups.firstOrNull { it.isPassword || it.name.contains("pass", true) }
    val ssid = ssidGroup?.let { values[it.name] ?: it.first.value }.orEmpty().trim()
    val psk = pskGroup?.let { values[it.name] ?: it.first.value }.orEmpty()
    if (ssid.isEmpty()) {
        return "请填写 Wi-Fi 名称 SSID，或先点“让盒子扫描周边 Wi-Fi”选一个网络。"
    }
    if (psk.length < 8) return "WPSD 新建 Wi-Fi 连接要求密码 8–63 位，请填写完整密码。"
    return null
}

private fun validateValues(
    form: ConfigureForm,
    values: Map<String, String>,
    duplex: Boolean
): String? {
    val callsign = values[FIELD_CALLSIGN].orEmpty().trim()
    if (callsign.isEmpty()) return "请填写呼号 Callsign"
    if (!Regex("^[A-Za-z0-9/]{2,10}$").matches(callsign)) {
        return "呼号只能包含字母、数字或 /，长度 2-10 位"
    }

    val dmrId = values[FIELD_DMR_ID].orEmpty().trim()
    if (form.has(FIELD_DMR_ID) && dmrId.isNotEmpty() && !Regex("^\\d{3,9}$").matches(dmrId)) {
        return "DMR ID 应为 3-9 位数字"
    }

    if (duplex) {
        validateFrequency("接收频率", values[FIELD_FREQ_RX])?.let { return it }
        validateFrequency("发射频率", values[FIELD_FREQ_TX])?.let { return it }
    } else {
        validateFrequency("频率", values[FIELD_FREQ])?.let { return it }
    }

    val colorCode = values[FIELD_COLOR_CODE].orEmpty().trim()
    if (colorCode.isNotEmpty()) {
        val code = colorCode.toDoubleOrNull() ?: return "色码不是有效数字"
        if (code < 0 || code > 15) return "色码范围应为 0-15"
    }
    return null
}

private fun validateFrequency(label: String, value: String?): String? {
    val text = value.orEmpty().trim()
    if (text.isEmpty()) return null
    val mhz = text.toDoubleOrNull() ?: return "$label 不是有效数字（单位 MHz）"
    if (mhz < 26.0 || mhz > 1300.0) return "$label 超出范围（26-1300 MHz）"
    return null
}
