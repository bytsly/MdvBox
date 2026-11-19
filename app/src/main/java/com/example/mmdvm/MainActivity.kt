package com.example.mmdvm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.mmdvm.ui.ConfigWizardScreen
import com.example.mmdvm.ui.DeviceDetailScreen
import com.example.mmdvm.ui.DeviceScanScreen
import com.example.mmdvm.ui.WebPageScreen
import com.example.mmdvm.ui.theme.MmdvmTheme

private sealed interface Route {
    data object Scan : Route
    data class Detail(val device: MmdvmDevice) : Route
    data class Wizard(val device: MmdvmDevice) : Route
    data class Page(
        val device: MmdvmDevice,
        val url: String,
        val title: String,
        val exportable: Boolean
    ) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MmdvmTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var route by remember { mutableStateOf<Route>(Route.Scan) }

    when (val current = route) {
        Route.Scan -> DeviceScanScreen(
            onOpen = { device -> route = Route.Detail(device) }
        )

        is Route.Detail -> DeviceDetailScreen(
            device = current.device,
            onOpenPage = { url, title, exportable ->
                route = Route.Page(current.device, url, title, exportable)
            },
            onOpenWizard = { route = Route.Wizard(current.device) },
            onBack = { route = Route.Scan }
        )

        is Route.Wizard -> ConfigWizardScreen(
            device = current.device,
            onBack = { route = Route.Detail(current.device) },
            onOpenWebConfig = {
                route = Route.Page(current.device, "${current.device.url}/admin/", "网页配置", true)
            }
        )

        is Route.Page -> WebPageScreen(
            host = current.device.host,
            url = current.url,
            initialTitle = current.title,
            exportable = current.exportable,
            onClose = { route = Route.Detail(current.device) }
        )
    }
}
