package com.example.mmdvm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mmdvm.BoxDetector
import com.example.mmdvm.BoxInfo
import com.example.mmdvm.BoxSystem
import com.example.mmdvm.CredentialStore
import com.example.mmdvm.Credentials
import com.example.mmdvm.MmdvmDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    device: MmdvmDevice,
    onOpenPage: (url: String, title: String, exportable: Boolean) -> Unit,
    onOpenWizard: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showCredentialDialog by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(CredentialStore.get(context, device.host).username) }
    var password by remember { mutableStateOf(CredentialStore.get(context, device.host).password) }
    var passwordVisible by remember { mutableStateOf(false) }

    var detecting by remember { mutableStateOf(true) }
    var boxInfo by remember { mutableStateOf<BoxInfo?>(null) }
    var detectError by remember { mutableStateOf<String?>(null) }
    var detectRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(device.host, detectRequest) {
        detecting = true
        detectError = null
        try {
            boxInfo = BoxDetector.detect(device.host, CredentialStore.get(context, device.host))
        } catch (e: Exception) {
            detectError = e.message ?: e.javaClass.simpleName
        } finally {
            detecting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.host) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回设备列表"
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = device.title?.takeIf { it.isNotBlank() } ?: device.type,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${device.type} · ${device.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "系统：${systemText(detecting, boxInfo)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    detectError?.let { error ->
                        Text(
                            text = "系统检测失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!detecting && boxInfo?.system != BoxSystem.UNKNOWN) {
                            Text(
                                text = when {
                                    boxInfo?.wizardSupported == true -> "该系统的原生配置向导已适配"
                                    else -> "该系统暂只支持网页配置"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { detectRequest++ },
                            enabled = !detecting
                        ) {
                            Text(if (detecting) "检测中…" else "重新检测")
                        }
                    }
                }
            }

            Button(
                onClick = onOpenWizard,
                enabled = !detecting && boxInfo?.wizardSupported == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (boxInfo?.wizardSupported == true) "配置向导（原生）" else "配置向导（仅支持 WPSD / Pi-Star）")
            }

            Button(
                onClick = { onOpenPage(device.url, "通联状态", false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看通联状态")
            }

            OutlinedButton(
                onClick = { onOpenPage("${device.url}/admin/", "网页配置", true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("网页配置（后台）")
            }

            OutlinedButton(
                onClick = {
                    val saved = CredentialStore.get(context, device.host)
                    username = saved.username
                    password = saved.password
                    showCredentialDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("后台账号：$username")
            }

            Text(
                text = "出厂默认账号为 pi-star / raspberry。账号按设备单独保存，只存在本机。" +
                    "配置向导会模拟登录盒子网页，在 Configuration 页填好后提交，" +
                    "等同手工点击 Apply Changes，不会直接改写配置文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCredentialDialog) {
        AlertDialog(
            onDismissRequest = { showCredentialDialog = false },
            title = { Text("后台登录账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                text = if (passwordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { passwordVisible = !passwordVisible }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CredentialStore.save(
                        context = context,
                        host = device.host,
                        credentials = Credentials(username.trim(), password)
                    )
                    showCredentialDialog = false
                    detectRequest++
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    username = CredentialStore.DEFAULT_USERNAME
                    password = CredentialStore.DEFAULT_PASSWORD
                }) {
                    Text("填入默认值")
                }
            }
        )
    }
}

private fun systemText(detecting: Boolean, info: BoxInfo?): String = when {
    detecting -> "检测中…"
    info == null || info.system == BoxSystem.UNKNOWN -> "未识别"
    else -> info.displayName
}
