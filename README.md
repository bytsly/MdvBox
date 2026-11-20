# MDVBOX

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="MDVBOX" />
</p>

局域网 MMDVM 盒子扫描、后台配置与通联状态查看的 Android 原生客户端。
面向使用 WPSD / Pi-Star 固件的树莓派热点盒子，无需电脑即可在手机上完成发现、配置和监控。

- 开发者：BH2VLF
- 当前版本：1.44
- 适用固件：WPSD、Pi-Star（原生配置向导）、J-STAR、MMDVM（仅网页配置）

## 功能概览

### 设备发现

- 自动扫描手机所在局域网的 1~254 号地址，并发探测 80 端口并识别固件类型。
- 识别失败时支持手动输入 IP 直连。
- 详见 [LanScanner.kt](app/src/main/java/com/example/mmdvm/LanScanner.kt) 与 [BoxSystem.kt](app/src/main/java/com/example/mmdvm/BoxSystem.kt)。

### 系统识别

通过首页 HTML 关键字识别 WPSD / Pi-Star / J-STAR / MMDVM，并解析版本号；首页失败时退回 `/admin/` 登录页继续判断。识别结果决定后续是否启用原生配置向导。

### 原生配置向导

针对 WPSD 与 Pi-Star（两者后台同源）提供分步向导，界面为原生 Material 3 组件，无需缩放网页：

1. Wi-Fi 网络（支持触发盒子扫描 SSID）
2. 基本设置（呼号、DMR ID、收发模式）
3. 工作模式（DMR / D-Star / YSF / P25 / NXDN / POCSAG / 各类桥接 / CWID）
4. 射频设置（频率、色码）
5. DMR 网络
6. 调制解调器（硬件型号、串口、波特率）
7. 屏幕与时区
8. 确认保存

向导通过模拟登录盒子网页后台，在 Configuration 页填写表单后整体提交，等同于手工点击 `Apply Changes`，不会直接改写配置文件。提交后会重新读取并校验关键字段是否写入成功。

实现见 [WpsdConfigurePage.kt](app/src/main/java/com/example/mmdvm/WpsdConfigurePage.kt) 与 [ui/ConfigWizardScreen.kt](app/src/main/java/com/example/mmdvm/ui/ConfigWizardScreen.kt)。

### 配置备份与还原

- 进入向导时自动备份当前表单快照，内容未变化不重复记录。
- 每台设备最多保留 6 份快照，可手动还原或删除。
- 还原时整表回放，等同于在网页后台重新点一次 `Apply Changes`。
- 实现见 [ConfigBackupStore.kt](app/src/main/java/com/example/mmdvm/ConfigBackupStore.kt)。

### 网页后台容器

- 内嵌 WebView，自动完成 HTTP Basic 认证。
- 按页面真实宽度注入 viewport，把桌面布局等比缩放到手机屏幕，保留双指缩放。
- 支持导出当前页面 HTML 到系统「下载」目录，便于后续适配新固件。
- 实现见 [ui/WebPageScreen.kt](app/src/main/java/com/example/mmdvm/ui/WebPageScreen.kt) 与 [ui/WebViewUtils.kt](app/src/main/java/com/example/mmdvm/ui/WebViewUtils.kt)。

### 凭据管理

- 后台账号按设备 IP 分别保存在本机 SharedPreferences，默认使用 `pi-star / raspberry`。
- 出厂默认密码未被修改时，盒子会拒绝继续操作，App 会提示先登录网页后台修改密码。
- 实现见 [CredentialStore.kt](app/src/main/java/com/example/mmdvm/CredentialStore.kt)。

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose（Material 3，Compose BOM 2024.10.00）
- Android Gradle Plugin 8.6.1
- compileSdk 35 / minSdk 29 / targetSdk 35
- 纯系统依赖，无第三方网络/HTML 库：
  - 自实现 HTTP 客户端：Basic 认证、Cookie 会话维护、multipart 上传、按 HTTP 头 / meta 自动识别字符编码（UTF-8 / GBK 回退）。见 [BoxHttpClient.kt](app/src/main/java/com/example/mmdvm/BoxHttpClient.kt)。
  - 自实现 HTML 表单解析：保留字段原始顺序（PHP `$_POST` 顺序会影响配置段落顺序）。见 [HtmlForm.kt](app/src/main/java/com/example/mmdvm/HtmlForm.kt)。
  - 行级 INI 文本编辑：只替换指定键值，保留注释与段落顺序。见 [IniText.kt](app/src/main/java/com/example/mmdvm/IniText.kt)。
  - Pi-Star / WPSD 字段名别名统一，提交时还原为网页原名回放。见 [WpsdConfigurePage.kt](app/src/main/java/com/example/mmdvm/WpsdConfigurePage.kt)。
- 支持 Android 12+ 动态取色（Material You），低版本回退自定义蓝绿配色。见 [ui/theme/Theme.kt](app/src/main/java/com/example/mmdvm/ui/theme/Theme.kt)。

## Android 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问盒子后台、扫描局域网 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 获取本机 IP、触发盒子 Wi-Fi 扫描 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Android 系统要求，扫描 Wi-Fi 结果需位置权限 |

## 目录结构

```
mdvbox/
├── app/
│   ├── build.gradle.kts              # 应用模块配置、版本号、exportApk 任务
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/mmdvm/
│       │   ├── MainActivity.kt       # 路由与入口
│       │   ├── LanScanner.kt         # 局域网扫描
│       │   ├── BoxSystem.kt          # 固件识别
│       │   ├── BoxHttpClient.kt      # HTTP 客户端 + 文本解码
│       │   ├── CredentialStore.kt    # 后台账号存储
│       │   ├── HtmlForm.kt           # HTML 表单解析
│       │   ├── IniText.kt            # INI 行级编辑
│       │   ├── WpsdConfig.kt         # MMDVMHost 配置读写
│       │   ├── WpsdConfigurePage.kt  # Configuration 页表单解析与回放
│       │   ├── ConfigBackupStore.kt  # 配置快照存储
│       │   └── ui/
│       │       ├── DeviceScanScreen.kt
│       │       ├── DeviceDetailScreen.kt
│       │       ├── ConfigWizardScreen.kt
│       │       ├── WebPageScreen.kt
│       │       ├── WebViewUtils.kt
│       │       └── theme/Theme.kt
│       └── res/                      # 图标、字符串、主题
├── apk/                              # 导出的 APK
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 构建

需要本机已安装 Android SDK（compileSdk 35）与 JDK 17。

```bash
# Debug 构建
./gradlew assembleDebug

# 导出带版本号的 APK 到 apk/ 目录（Debug 签名）
./gradlew exportApk
```

输出文件名形如 `apk/MDVBOX_v1.44.apk`。版本号在 [app/build.gradle.kts](app/build.gradle.kts) 中维护，每次改动递增 0.01。

## 出厂默认账号

盒子默认后台账号为 `pi-star / raspberry`。出于安全限制，盒子仍在使用默认密码时拒绝任何配置写入，请先通过「网页配置（后台）」登录并修改密码，再使用原生配置向导。

## 许可与版权

© 2026 BH2VLF 版权所有。
本项目源码供学习与个人使用，APK 不得用于商业转售。
