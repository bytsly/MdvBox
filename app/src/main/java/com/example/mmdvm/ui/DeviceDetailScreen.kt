package com.example.mmdvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mmdvm.CredentialStore
import com.example.mmdvm.Credentials
import com.example.mmdvm.MmdvmDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    device: MmdvmDevice,
    onOpenPage: (url: String, title: String, exportable: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showCredentialDialog by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(CredentialStore.get(context, device.host).username) }
    var password by remember { mutableStateOf(CredentialStore.get(context, device.host).password) }

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
                }
            }

            Button(
                onClick = { onOpenPage("${device.url}/admin/", "配置盒子", true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("配置盒子")
            }

            Button(
                onClick = { onOpenPage(device.url, "通联状态", false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看通联状态")
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
                text = "出厂默认账号为 pi-star / raspberry。账号按设备单独保存，只存在本机。",
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
                        visualTransformation = PasswordVisualTransformation(),
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
