package com.example.mmdvm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mmdvm.LanScanner
import com.example.mmdvm.MmdvmDevice
import com.example.mmdvm.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(onOpen: (MmdvmDevice) -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableIntStateOf(0) }
    var localIp by remember { mutableStateOf<String?>(null) }
    var foundDevices by remember { mutableStateOf(emptyList<MmdvmDevice>()) }
    var message by remember { mutableStateOf("正在扫描局域网…") }
    var manualHost by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }

    fun startScan() {
        if (scanning) return
        scanning = true
        scannedCount = 0
        foundDevices = emptyList()
        message = "正在扫描局域网…"
        scope.launch {
            val ip = LanScanner.localIpv4()
            localIp = ip
            if (ip == null) {
                scanning = false
                message = "未检测到局域网地址，请先连接盒子所在的 Wi-Fi"
                return@launch
            }
            LanScanner.scan(
                subnetPrefix = ip.substringBeforeLast('.'),
                onProgress = { scannedCount = it },
                onFound = { device -> foundDevices = foundDevices + device }
            )
            scanning = false
            message = if (foundDevices.isEmpty()) {
                "没有发现盒子，请确认手机与盒子在同一网段，或手动输入 IP"
            } else {
                "扫描完成，共发现 ${foundDevices.size} 台设备"
            }
        }
    }

    LaunchedEffect(Unit) {
        startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showAbout = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "关于"
                        )
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

            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = localIp?.let { "本机局域网地址：$it" } ?: "本机局域网地址：未获取",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanning) {
                        LinearProgressIndicator(
                            progress = { scannedCount.toFloat() / LanScanner.HOST_COUNT },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(
                        onClick = { startScan() },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (scanning) "扫描中… $scannedCount/${LanScanner.HOST_COUNT}" else "重新扫描")
                    }
                }
            }

            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                label = { Text("手动输入盒子 IP") },
                placeholder = { Text("例如 192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val host = manualHost.trim()
                    if (host.isNotEmpty()) {
                        onOpen(MmdvmDevice(host = host, type = "手动连接"))
                    }
                },
                enabled = manualHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开该地址")
            }

            Text("发现的设备", style = MaterialTheme.typography.titleSmall)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(foundDevices, key = { it.host }) { device ->
                    DeviceRow(device = device, onClick = { onOpen(device) })
                }
            }

            Text(
                text = stringResource(R.string.copyright),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text("关闭")
                }
            },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.app_version))
                    Text(stringResource(R.string.developer))
                    Text(stringResource(R.string.copyright))
                    Text(
                        text = "局域网 MMDVM 盒子扫描、后台配置与通联状态查看工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Composable
private fun DeviceRow(device: MmdvmDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.title?.takeIf { it.isNotBlank() } ?: device.type,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${device.type} · ${device.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}
