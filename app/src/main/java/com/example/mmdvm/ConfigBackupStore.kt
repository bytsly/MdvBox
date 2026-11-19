package com.example.mmdvm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次配置快照：记录网页表单里所有可见字段的当前值。
 * 还原时整表重提交，等同于在网页后台重新点一次 Apply Changes。
 */
internal data class ConfigSnapshot(
    val time: Long,
    val label: String,
    val values: Map<String, String>,
    val radios: Map<String, String>,
    val checks: Map<String, Boolean>
) {
    fun toEdits(): ConfigureEdits =
        ConfigureEdits(values = values, radioValues = radios, checkboxes = checks)

    fun timeText(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

/** 快照按设备地址存在手机本地，每台盒子最多保留几份。 */
internal object ConfigBackupStore {

    private const val PREFS = "config_backups"
    private const val MAX = 6

    fun capture(
        form: ConfigureForm,
        label: String,
        time: Long = System.currentTimeMillis()
    ): ConfigSnapshot {
        val values = mutableMapOf<String, String>()
        val radios = mutableMapOf<String, String>()
        val checks = mutableMapOf<String, Boolean>()
        form.fields
            .filter { it.kind != ConfigureFieldKind.HIDDEN }
            .forEach { field ->
                when {
                    field.kind == ConfigureFieldKind.CHECKBOX && field.inputType == "radio" -> {
                        if (field.checked) radios[field.name] = field.value
                    }

                    field.kind == ConfigureFieldKind.CHECKBOX -> checks[field.name] = field.checked

                    // 密码不回显时值为空，不存空值，还原时也就不会把原密码清掉
                    field.inputType == "password" -> {
                        if (field.value.isNotBlank()) values[field.name] = field.value
                    }

                    else -> values[field.name] = field.value
                }
            }
        return ConfigSnapshot(time, label, values, radios, checks)
    }

    fun list(context: Context, host: String): List<ConfigSnapshot> {
        val raw = prefs(context).getString(key(host), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                ConfigSnapshot(
                    time = obj.optLong("time"),
                    label = obj.optString("label"),
                    values = obj.optJSONObject("values").toStringMap(),
                    radios = obj.optJSONObject("radios").toStringMap(),
                    checks = obj.optJSONObject("checks").toBoolMap()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, host: String, snapshot: ConfigSnapshot) {
        val list = (listOf(snapshot) + list(context, host)).take(MAX)
        prefs(context).edit().putString(key(host), encode(list).toString()).apply()
    }

    fun remove(context: Context, host: String, time: Long) {
        val list = list(context, host).filterNot { it.time == time }
        prefs(context).edit().putString(key(host), encode(list).toString()).apply()
    }

    /** 自动备份：内容与最近一份相同就不重复记录，免得每次进向导都多一条。 */
    fun saveIfChanged(context: Context, host: String, snapshot: ConfigSnapshot): Boolean {
        val latest = list(context, host).firstOrNull()
        if (latest != null &&
            latest.values == snapshot.values &&
            latest.radios == snapshot.radios &&
            latest.checks == snapshot.checks
        ) {
            return false
        }
        save(context, host, snapshot)
        return true
    }

    private fun encode(list: List<ConfigSnapshot>): JSONArray = JSONArray().apply {
        list.forEach { snapshot ->
            put(
                JSONObject().apply {
                    put("time", snapshot.time)
                    put("label", snapshot.label)
                    put("values", JSONObject(snapshot.values as Map<*, *>))
                    put("radios", JSONObject(snapshot.radios as Map<*, *>))
                    put("checks", JSONObject(snapshot.checks as Map<*, *>))
                }
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(host: String) = "host_$host"

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { optString(it) }
    }

    private fun JSONObject?.toBoolMap(): Map<String, Boolean> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { optBoolean(it) }
    }
}
