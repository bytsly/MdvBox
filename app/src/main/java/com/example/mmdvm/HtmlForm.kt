package com.example.mmdvm

import java.net.URI

internal enum class FormFieldType { HIDDEN, TEXT, CHECKBOX, RADIO, OTHER }

internal data class FormField(
    val name: String,
    val value: String,
    val type: FormFieldType
)

internal data class HtmlForm(
    val action: String,
    val method: String,
    val fields: List<FormField>,
    val textareaName: String?,
    val textareaText: String?
)

/**
 * 极简 HTML 表单解析：只服务于盒子后台的配置页/编辑页。
 * 保留字段原始顺序，提交时按顺序回放（PHP 的 $_POST 顺序会影响配置文件的段落顺序）。
 */
internal object HtmlFormParser {

    private val formRegex = Regex(
        "<form\\b([^>]*)>(.*?)</form>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val inputRegex = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val textareaRegex = Regex(
        "<textarea\\b([^>]*)>(.*?)</textarea>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attrRegex = Regex(
        "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))"
    )

    fun parse(html: String, pageUrl: String): HtmlForm? {
        var best: HtmlForm? = null
        var bestScore = 0

        formRegex.findAll(html).forEach { formMatch ->
            val formAttrs = attributes(formMatch.groupValues[1])
            val inner = formMatch.groupValues[2]
            val fields = mutableListOf<FormField>()

            inputRegex.findAll(inner).forEach { inputMatch ->
                val attrs = attributes(inputMatch.groupValues[1])
                val name = attrs["name"]?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                val type = attrs["type"]?.lowercase()?.trim().orEmpty()
                if (type in SKIPPED_TYPES) return@forEach

                val kind = when (type) {
                    "hidden" -> FormFieldType.HIDDEN
                    "checkbox" -> FormFieldType.CHECKBOX
                    "radio" -> FormFieldType.RADIO
                    "", "text", "password", "number", "search", "email", "url", "tel" -> FormFieldType.TEXT
                    else -> FormFieldType.OTHER
                }
                val isCheckable = kind == FormFieldType.CHECKBOX || kind == FormFieldType.RADIO
                if (isCheckable && !attrs.containsKey("checked")) return@forEach
                fields += FormField(
                    name = name,
                    value = attrs["value"] ?: if (isCheckable) "on" else "",
                    type = kind
                )
            }

            val textarea = textareaRegex.find(inner)
            val textareaName = textarea?.let { attributes(it.groupValues[1])["name"]?.trim() }
                ?.takeIf { it.isNotEmpty() }
            val textareaText = textarea?.let { HttpText.unescape(it.groupValues[2]) }

            val score = fields.size + if (textareaName != null) 3 else 0
            if (score > bestScore) {
                bestScore = score
                best = HtmlForm(
                    action = resolve(pageUrl, formAttrs["action"].orEmpty()),
                    method = formAttrs["method"]?.lowercase() ?: "post",
                    fields = fields,
                    textareaName = textareaName,
                    textareaText = textareaText
                )
            }
        }

        return best?.takeIf { it.fields.isNotEmpty() || it.textareaName != null }
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

    private val SKIPPED_TYPES = setOf("submit", "reset", "button", "image", "file")
}
