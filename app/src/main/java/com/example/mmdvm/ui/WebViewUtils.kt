package com.example.mmdvm.ui

import android.webkit.WebView

/**
 * 盒子后台与部分第三方站点多为桌面布局，通常没有 viewport 声明。
 * 按页面真实宽度注入 viewport，把整页等比缩放到屏幕宽度，同时保留双指缩放。
 */
internal fun fitToScreen(view: WebView) {
    val widthPx = view.width
    if (widthPx <= 0) return
    val cssWidth = (widthPx / view.resources.displayMetrics.density).toInt()

    val script = """
        (function (screenWidth) {
            var root = document.documentElement;
            var body = document.body;
            var contentWidth = Math.max(
                root.scrollWidth,
                body ? body.scrollWidth : 0,
                screenWidth
            );
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.setAttribute('name', 'viewport');
                (document.head || root).appendChild(meta);
            }
            var scale = Math.min(1, screenWidth / contentWidth);
            meta.setAttribute('content',
                'width=' + contentWidth +
                ', initial-scale=' + scale +
                ', maximum-scale=4, user-scalable=yes');
        })($cssWidth);
    """.trimIndent()

    view.evaluateJavascript(script, null)
}
