package com.example.mmdvm.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mmdvm.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

/**
 * 通用的盒子网页容器：自动完成 Basic 认证、整页缩放到手机屏幕。
 * exportable 为 true 时，标题栏会提供「导出」按钮，把当前页面 HTML 存到系统下载目录，
 * 便于后续根据真实表单结构做原生配置界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPageScreen(
    host: String,
    url: String,
    initialTitle: String,
    exportable: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentials = remember(host) { CredentialStore.get(context, host) }

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(initialTitle) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        val current = webView
        if (current != null && current.canGoBack()) current.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    fun exportPage() {
        val view = webView ?: return
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            val html = runCatching { JSONTokener(raw).nextValue() as String }.getOrNull()
            if (html == null) {
                exportMessage = "导出失败：页面还没有加载完成"
                return@evaluateJavascript
            }
            scope.launch {
                val fileName = withContext(Dispatchers.IO) { saveHtml(context, host, html) }
                exportMessage = if (fileName != null) {
                    "已保存到「下载」目录：\n$fileName"
                } else {
                    "保存失败，请稍后重试"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(pageTitle) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        if (exportable) {
                            TextButton(onClick = { exportPage() }) {
                                Text("导出")
                            }
                        }
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新"
                            )
                        }
                    }
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { webView?.let { view -> fitToScreen(view) } },
            factory = { webContext ->
                WebView(webContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            handler?.proceed(credentials.username, credentials.password)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            progress = 5
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 100
                            if (view != null) fitToScreen(view)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            }
        )
    }

    exportMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { exportMessage = null },
            confirmButton = {
                TextButton(onClick = { exportMessage = null }) {
                    Text("知道了")
                }
            },
            title = { Text("导出页面结构") },
            text = { Text(message) }
        )
    }
}

private fun saveHtml(context: Context, host: String, html: String): String? {
    return runCatching {
        val fileName = "mmdvm_${host.replace('.', '_')}_${System.currentTimeMillis()}.html"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        fileName
    }.getOrNull()
}
