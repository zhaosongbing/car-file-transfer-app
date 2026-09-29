# 车机文件互传（CarFileTransfer）

手机扫码 → 通过局域网把文件真实传输到车机（Android）的 App。无 Gradle、无第三方依赖，
由 Android SDK 命令行工具（aapt2 / javac / d8 / zipalign / apksigner）直接构建。

车机端 UI 全部由 **MIUIX 组件库**原生实现（无 WebView 外壳），APK 安装走 **ADB / shell**，
并支持生成**车机热点二维码**（使用车机原本的账号密码）。

## 下载与 Release 附件命名

每个 Release 同时提供**两个附件，内容完全相同**（同一个安装包，sha256 一致）：

| 附件 | 用途 |
| --- | --- |
| `CarFileTransfer-v9.8.1.apk` | 规范名称，**推荐引用**（含版本号，便于区分与缓存） |
| `CarFileTransfer.apk` | 兼容别名，历史书签 / 二维码 / 外部脚本继续可用，不会被删除 |

命名规则：`CarFileTransfer-<version>.apk`，其中 `<version>` 取 Release tag（如 `v9.8.1`）。
各历史版本均已补齐，例如 v9.1.0 / v9.2.0 / … / v9.8.1。

最新稳定版（两个链接指向同一个 APK）：

```
https://github.com/zhaosongbing/car-file-transfer-app/releases/download/v9.8.1/CarFileTransfer-v9.8.1.apk
https://github.com/zhaosongbing/car-file-transfer-app/releases/download/v9.8.1/CarFileTransfer.apk
```

应用内「关于 → 检测更新」读取的正是这些 Release 附件，因此该仓库必须保持**公开**（免鉴权即可读取）；
升级时优先取带版本号的附件，取不到时自动回落到别名链接。

## 工作流程

1. 车机打开 App → 弹出「选择连接方式」→ 二选一：
   - **手机连接车机热点**：车机开热点，二维码为 `WIFI:T:..;S:..;P:..;;`，手机扫码即加入
   - **车机连接手机热点**：双方同一 Wi-Fi，二维码为 `http://<车机IP>:8899`
2. `TransferService` 前台服务在 `8899` 端口启动 HTTP 服务（常驻通知，页面销毁也不断）
3. 二维码卡片可在「热点连接码 / 传输地址码」之间切换
4. 手机扫码 → 打开由车机下发的**双向门户页** → 两个方向任选：
   - **发送到车机**：选文件 → HTTP POST 上传（带真实进度）
   - **从车机下载**：勾选车机上的文件 → 逐个保存到手机「下载」目录
5. 落盘 / 读取目录：`/Android/data/com.zsb.carfiletransfer/files/received/`
6. 车机端查看列表、打开 / **ADB 安装** APK、一键清理（带确认弹窗）

## 界面结构（严格对齐设计稿 1194×834，且响应式）

所有界面元素均由 MIUIX 组件构建（容器用 LinearLayout / FrameLayout / ScrollView，
对应 Compose 的 Row / Box / Column；其余一律 MIUIX 组件）。

响应式由 `MiuixWindowSizeClass` 驱动，断点 `<600dp / 600–839dp / >=840dp`：

| 尺寸类 | 屏幕内边距 | 顶栏高 | 二维码卡 | 布局 |
| --- | --- | --- | --- | --- |
| EXPANDED（≥840dp，设计稿 1194） | 48 | 92 | 460 固定 | 双栏（二维码卡 + 侧栏） |
| MEDIUM（600–839dp） | 32 | 72 | `min(460, 42% 内容宽)` | 双栏 |
| COMPACT（<600dp） | 20 | 56 | 满宽 | 单栏堆叠 + 滚动 |

旋转屏幕时 `onConfigurationChanged` 重建视图树并重算 token，不会重启传输服务。

| 设计稿屏 | 实现 |
| --- | --- |
| ① 传输主页（未连接占位 / 已连接二维码） | `MiuixTopAppBar` + `MiuixCard`(r32) + `MiuixQrView`(300dp) + `MiuixTabRow`(chip) + `MiuixButton` |
| ② 选择连接方式弹窗 | `MiuixDialog`(540dp, r24, padding32) + `MiuixCard` 选项 + `MiuixButton` |
| ⑦ 已接收文件列表 | `MiuixTopAppBar` + `MiuixTextField`(搜索) + `MiuixTabRow`(分类胶囊) + `MiuixListItem` |
| ⑧ 清理确认弹窗 | `MiuixDialog`(480dp, r24, padding28) + 双 `MiuixButton` |
| ⑨ 文件详情 | `MiuixTopAppBar` + `MiuixCard` ×3 + `MiuixButton` ×2 + `MiuixText` 操作 |

设计稿 token：顶栏高 92、屏幕内边距 48、主栏间距 40、二维码卡宽 460 / 内边距 40 / 圆角 32、
普通卡圆角 24、列表行圆角 16、类型块圆角 12、胶囊 9999、按钮 100；
字号 32 / 26 / 24 / 22 / 20 / 18 / 16 / 15 / 14 / 13 / 12 全部按稿落地。

配色走 MIUIX token（primary / error / success / warning / surface / outline …），
文件类型色由 `MiuixTheme.fileTypeColor()` 映射到 MIUIX 语义色。

顶栏右侧为 **ADB 状态胶囊**（已连接 / 待连接 / 未连接，3 秒轮询刷新），点击可展开
详情：adbd 状态、TCP 端口、是否监听、已连接客户端 IP，并可一键开启网络调试 5555。
未选择连接方式时二维码区域显示「未连接」占位。

## 打开设备热点

点击「打开热点」会按策略链依次尝试**真正开启设备热点**，全部走反射 + 逐级降级：

1. `ConnectivityManager#startTethering(TETHERING_WIFI)` — 真正的系统网络共享
2. `WifiManager#startSoftAp(SoftApConfiguration)` — 系统软 AP（Android 11+）
3. `WifiManager#setWifiApEnabled(WifiConfiguration, true)` — 旧版软 AP
4. `WifiManager#startLocalOnlyHotspotWithConfiguration` — 应用托管 AP，**沿用车机原本的 SSID / 密码**（Android 13+）
5. `WifiManager#startLocalOnlyHotspot` — 应用托管 AP（随机 SSID）
6. 全部失败 → 弹窗提示并可跳转系统热点设置页手动开启

账号密码优先读取车机原有配置（`getSoftApConfiguration()` → `getWifiApConfiguration()`
→ 解析 `softap.conf`）；热点状态通过 `getWifiApState()` 实时判定，二维码里的传输地址
取 AP 网卡（`ap0` / `wlan1` …）上的 IP。

## 反向传输（车机 → 手机）

同一个端口同时提供两个方向的接口，手机端门户页用标签切换：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 手机端门户页（H5，含上传 / 下载两个标签） |
| GET | `/info` | `{"device","free","direction"}` |
| GET | `/files` | 车机上已存文件的 JSON 列表 |
| GET | `/download?name=` | 下载文件，支持 `Accept-Ranges` / 单区间 `Range`（206 + Content-Range） |
| HEAD | `/download?name=` | 只回响应头，不传实体 |
| POST | `/upload?name=` | 上传落盘 |

文件名按 UTF-8 处理，中文名（`测试 文件 (1).apk`）上传→下载可完整回转；
路径穿越类名字（`../../Windows/win.ini`）仍返回 404。

## 前台服务保活

`TransferService`（started foreground service，`foregroundServiceType=dataSync`，
`stopWithTask=false`）持有 `FileRepository` 与 `HttpFileServer`：

- 常驻通知显示**实时传输地址**与**已接收文件数**，点击回到主界面，内置「停止服务」动作
- 旋转屏幕、切后台、息屏都不会中断正在进行的传输
- Activity 的 `onDestroy` 不再停服务 / 关热点；生命周期完全解耦
- 首次进入会引导加入**电池优化白名单**，进一步避免息屏冻结
- 启动依次为：`startForegroundService()` → `startForeground(id, n, TYPE_DATA_SYNC)`，
  ROM 不接受带类型版本时自动降级为普通 `startForeground()`

## 系统栏与刘海适配

targetSdk 36 起 Android 强制 edge-to-edge，自定义顶栏会被状态栏压住。处理方式：

- 新增 `AppTheme`（`Theme.Material.Light.NoActionBar`），状态栏 / 导航栏设为透明
- `MainActivity#applySystemBars` 用 `WindowInsets` 取 `systemBars` 与 `displayCutout`
  的并集，按左/上/右/下四边给根视图加 padding；低于 API 29 走 `getSystemWindowInset*`
- Android 9+ 设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，刘海两侧也不裁 halves


```
app/src/main/
  AndroidManifest.xml
  java/com/zsb/carfiletransfer/
    MainActivity.java            车机端 UI（MIUIX）+ 热点/安装调度 + 系统栏适配
    TransferService.java         前台保活服务（常驻通知，托管传输服务）
    HttpFileServer.java          零依赖 HTTP 服务（门户页 / 上传 / 下载 Range / 列表）
    SoftApManager.java           设备热点开启（策略链）+ 配置读取 + AP 状态 + IP 发现
    AdbManager.java              ADB 状态检测、shell 安装、开启网络调试
    FileRepository.java          文件落盘、列表、清理、类型与 MIME 判断
    LocalFileProvider.java       免 androidx 的 content:// 文件共享
    miuix/                       MIUIX 组件库（Theme/Card/Text/Button/Switch/
                                 ListItem/TopAppBar/TabRow/Progress/Divider/
                                 Dialog/TextField/QrView/WindowSizeClass）
    qr/QrCode.java               纯 Java QR 编码器（含 RS 纠错，已交叉验证）
    res/                         图标（adaptive icon + 各密度 PNG）、colors、strings、styles
    assets/portal.html           手机端双向门户页（上传 / 下载，由车机下发）
  build.sh                       一键构建脚本
  tools/gen_icon.py              桌面图标生成
  tools/verify_qr.py             二维码编码器交叉验证（OpenCV 解码）
  tools/http_test/               传输协议离线验证（stub 拖起 HttpFileServer + curl 回归）
```

## 构建

需要 JDK 17 与 Android SDK（build-tools 36、platform android-36）。

```bash
# JDK 放到 ../tools/jdk-17.0.13+11，或按实际路径修改 build.sh 顶部
bash build.sh
```

产物：`build/CarFileTransfer.apk`（已 zipalign + v2/v3 签名，自签名 keystore，别名 `carfile`）。

### 签名密钥（不入库）

keystore 与其口令**都不会提交**（见 `push_repo.py` 的 `EXCLUDE_DIRS` 与密钥守卫）。二者一旦同时泄露，
任何人都能用同一证书签名 APK 并劫持应用内更新通道。

本地把口令写入 `keystore/pass.txt`（该文件随 `keystore/` 一并被排除），或导出环境变量：

```bash
export CARFILE_KS_PASS=你的口令
bash build.sh
```

首次构建若无 keystore 会自动生成；请妥善备份 `keystore/carfile.keystore`，丢失后新版将无法覆盖安装。

## 权限

网络：`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`
Wi-Fi / 热点：`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`NEARBY_WIFI_DEVICES`、
`OVERRIDE_WIFI_CONFIG`、`TETHER_PRIVILEGED`
定位（平台要求读取 Wi-Fi / 开热点必须具备）：`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`
保活：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、
`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
其他：`REQUEST_INSTALL_PACKAGES`、`WRITE_SETTINGS`

其中 `POST_NOTIFICATIONS`、`NEARBY_WIFI_DEVICES`（API 33+）与定位在首次启动时
运行时申请；未授权会提示可能受限，并引导加入电池优化白名单。

## 已知限制

- **热点账号密码读取**：优先反射 `getSoftApConfiguration()` →
  `getWifiApConfiguration()` → 解析 `softap.conf`。多数车机 ROM 会拦截隐藏 API，
  此时请在「设置 → 车机热点 → 手动指定账号密码」填写，配置会持久化保存。
- **开热点权限**：前三条策略（系统网络共享 / 系统软 AP）需要系统签名或 `TETHER_PRIVILEGED`
  等特权，普通三方 App 通常会被拒绝并自动降级到第 4/5 条（应用托管 AP）。降级后 SSID
  仍是车机原本的（Android 13+），功能不受影响，只是不共享车机的蜂窝/以太网出口。
  全部失败时仍可让手机与车机连同一 Wi-Fi，用界面上显示的地址传输。
- **ADB 安装**走 `pm install -r`（与 adb 同一路径）。非系统/无 shell 权限时会失败，
  App 会展示失败原因并回退到系统安装界面。
- ~~切后台后服务可能被回收~~：已由前台服务 + 电池优化白名单引导解决；极端情况下
  （用户手动「停止服务」或系统强杀）传输才会中断。
- 系统安装界面方式需先在设置中允许「安装未知应用」。
- **未真机验证**：开发环境无 Android 设备，传输协议已在离线环境下用 curl 回归
  （200/206/416/404/遍历防护/中文名回转全部通过），但 MIUIX 渲染、热点与 ADB 行为仍需上车确认。
