# Neural Coherence Tool

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

基于 [libxposed API 102](https://libxposed.github.io/api/index.html) 的 LSPosed 模块，
为方舟雷达的官方 APP [同调计划](https://www.tongdiaojihua.com/) 提供顺序互动。

## 无 Root 使用

> [!IMPORTANT]
> 本模块已经验证可通过 **NPatch** 在无 Root 设备上运行。该方式需要重新打包「同调计划」
> 宿主 APK，并使用 **Stealth** 绕过签名校验。
>
> **必须使用支持 libxposed API 102 的 NPatch `v1.0.7` 或后续版本。**
> 从 `v1.0.6` 或更旧版本升级时，NPatch 官方建议先卸载旧 Manager，再安装新版，以免
> 缓存或旧数据结构引发异常。
>
> 重新打包后的应用签名与官方版本不同，因此无法直接覆盖安装。请先卸载官方版本，并提前
> 确认账号和登录方式。

[查看完整的无 Root 安装教程](docs/ROOTLESS_GUIDE.md)

若设备已经 Root，也可以继续使用 LSPosed 2.x / libxposed API 102，无需重新打包宿主。

## 功能

### 好友状态扫描

- 分页读取完整好友列表，不依赖页面是否已滚动加载。
- 统计无色、蓝色、绿色和橙色四种当日互动状态。
- 扫描操作只读取数据，不发送好友互动。
- 严格校验响应结构、状态范围和好友 ID；接口不兼容时立即停止。

### 一键互动

- 无色状态发送 `ping`，绿色状态发送 `pong`。
- 蓝色和橙色表示当日已完成对应操作，自动跳过。
- 请求按顺序执行，间隔在 0.5 至 1.5 秒之间随机选择。
- 服务端提示请求频繁或返回 HTTP 429 时，将等待 20 秒并重试本次请求一次。
- 重试成功后继续处理剩余好友；其他请求失败或重试仍受限时立即停止任务。
- 运行期间可通过面板手动停止。

> [!NOTE]
> 考虑到批量互动可能给「同调计划」服务器带来额外压力，模块已适当延长自动互动间隔。
> 约 500 位待互动好友通常需要二十分钟左右，具体耗时会随好友数量、网络状况和服务端响应变化。

### 页面面板

- 控制面板根据 Flutter 语义节点的实际位置，自适应显示在「同调网络」与页头右侧操作入口之间。
- 同时兼容旧版「设置特别通讯」和新版「+添加好友」，会避让入口图标并根据可用空间自动调整宽度。
- 提供扫描、互动、停止和实时进度显示。
- 通过 Flutter 语义树识别真实页面，仅在「同调网络」主页显示。
- 弹窗、子页面、双击、滑动和系统手势不会被误判为主页。
- Activity 重建后恢复当前任务状态和进度。

### 实时进度通知

- 扫描和批量互动期间显示当前进度；批量互动通知可直接停止任务。
- Android 16 支持系统原生 `Notification.ProgressStyle` 和提升式实时更新；Android 14 至 15 使用普通进度通知。
- 任务结束时先在实时更新中显示“已完成”和聚合结果 1 分钟，随后转换为可清除的详细完成通知。
- 通知只展示好友总数、完成数和成功数，不包含好友姓名、ID、账号或会话信息。
- 目标应用未声明通知权限，因此实时更新由模块自身发布；首次使用需允许模块通知权限。

### 可靠性保护

- 批量任务启动后的 10 分钟内阻止再次批量执行，扫描功能不受影响。
- 开始任务前检查当前网络是否可用。
- 区分无网络、DNS/连接失败、超时和 TLS 安全连接错误。
- 不在日志中输出登录会话、完整好友 ID、请求签名或服务器响应正文。
- 不使用固定 Dart AOT 函数偏移，降低目标应用重新编译造成的失效风险。

## 兼容性

- LSPosed/libxposed API：102
- 无 Root 框架：NPatch `v1.0.7` 或后续支持 libxposed API 102 的版本
- Android：最低 API 29
- 架构：模块本身不包含 native 库
- 作用域：`com.linktech.arkradar`

已验证环境：同调计划 2.0.2–2.0.4、Android 16、LSPosed 2.0.2-it（API 102）。
无 Root 环境已验证 NPatch 本地模式、Stealth 签名校验绕过，以及扫描、`ping`、`pong` 和通知链路；
具体安装方式及签名变化带来的限制请参阅[无 Root 安装教程](docs/ROOTLESS_GUIDE.md)。
目标应用版本仅表示已完成测试的环境，并非硬编码版本白名单；目标应用更新后仍建议先执行只读扫描验证兼容性。

实时通知的提升样式目前主要针对 Android 16 / ColorOS 16 完成验证。HyperOS 等其他国内定制系统
可能存在进度条、状态胶囊或完成态刷新等显示细节差异；这不影响应用内面板、好友扫描和互动逻辑，
后续版本将继续补充不同系统的显示适配。

模块通过 Flutter Activity 注入面板，并通过目标应用现有登录会话访问其接口。若目标应用修改
接口路径、鉴权方式、字段名称或底部导航结构，模块会停止相关操作或需要重新适配。

## 项目结构

```text
.
|-- app/
|   |-- build.gradle.kts                 # Android 模块配置与本地构建参数
|   |-- src/main/
|       |-- AndroidManifest.xml          # Android 应用清单
|       |-- kotlin/io/github/neuralcoherence/probe/
|       |   |-- NeuralCoherenceModule.kt # libxposed 入口、Flutter 语义监测与面板
|       |   |-- LiveUpdateClient.kt      # 宿主进程通知桥接
|       |   |-- LiveUpdateContract.kt    # 进程间动作与字段约定
|       |   |-- LiveUpdateNotification.kt # 原生实时更新通知
|       |   |-- LiveUpdateReceiver.kt    # 模块端进度接收器
|       |   |-- LiveUpdateStopReceiver.kt # 通知停止操作
|       |   |-- LiveUpdateTransition.kt  # 完成态定时转换与兜底
|       |   |-- LiveUpdateTransitionReceiver.kt # 内部定时转换接收器
|       |   |-- NotificationPermissionActivity.kt # 通知设置与自检
|       |   `-- core/
|       |       |-- HeaderBoundsResolver.kt # 新版标题数量与面板边界计算
|       |       |-- InteractionEngine.kt # 扫描、互动、签名与错误映射
|       |       |-- InteractionRateLimit.kt # 请求间隔、限流识别与单次重试策略
|       |       |-- ModuleTaskCoordinator.kt # 扫描与互动任务互斥状态
|       |       |-- PanelLayoutCalculator.kt # 自适应面板布局
|       |       `-- SemanticPageClassifier.kt # 页面语义判定
|       |-- res/drawable/                # 通知图标
|       |-- res/values/strings.xml       # 模块名称和说明
|       `-- resources/META-INF/xposed/
|           |-- java_init.list           # libxposed Java 入口
|           |-- module.prop              # API 版本及模块属性
|           `-- scope.list               # 默认目标作用域
|   `-- src/test/kotlin/                  # Kotlin 纯逻辑回归测试
|-- docs/
|   |-- ROOTLESS_GUIDE.md                # NPatch 无 Root 图文安装教程
|   `-- images/                          # 教程图片
|-- build.gradle.kts                     # Android Gradle Plugin 与 Kotlin 配置
|-- gradle/wrapper/                      # Gradle 8.9 Wrapper
|-- gradlew / gradlew.bat                # 跨平台构建入口
|-- gradle.properties                    # Gradle 构建选项
|-- settings.gradle.kts                  # 仓库与模块声明
`-- README.md
```

## 本地配置

请求签名配置不会提交到版本库。开发者需要在项目根目录的 `local.properties` 中添加：

```properties
SYNC_SIGNING_SALT=your_local_signing_salt_here
```

也可以通过同名环境变量 `SYNC_SIGNING_SALT` 提供。缺少该值时项目仍可编译，但模块会拒绝
发送网络请求。`local.properties` 已包含在 `.gitignore` 中。

请勿提交以下内容：

- 登录会话、Cookie、请求签名材料或账号标识
- 设备序列号、好友数据、测试截图和日志
- 目标 APK、反编译输出、逆向工具目录或本机绝对路径
- 本地签名文件和签名密码

## 构建

使用 Android Studio 打开项目，同步 Gradle 后构建 `app` 模块；或使用仓库自带的 Gradle
Wrapper：

```shell
./gradlew :app:assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

正式发布使用本地 `signing.properties` 和 release keystore。两者均已被 Git 忽略，请在安全位置
备份；后续版本必须使用同一密钥签名。配置完成后运行：

```shell
./gradlew :app:assembleRelease
```

签名后的 APK 输出到 `app/build/outputs/apk/release/app-release.apk`。

安装后在 LSPosed 中启用模块，将作用域设置为目标应用，再强制停止并重新打开目标应用。

### 实时通知配置

1. 长按面板状态文字，或在“一键互动”确认框中点击“实时通知”。
2. 允许 `Neural Coherence Tool` 发送通知；Android 16 可继续允许“实时更新提升”。
3. 使用带后台管控的定制系统时，请允许 `Neural Coherence Tool` 自启动和后台运行。
4. 若使用 Freezer、Thanox 或其他冻结/后台管控工具，请将包名
   `io.github.neuralcoherence.probe` 加入后台启动与永不冻结白名单。

若通知进度中途停止而应用内任务已经完成，通常是模块进程被系统或第三方工具冻结。确认以上
白名单后，结束模块的旧进程并重新运行一次只读扫描即可，无需重启手机。

## 安全说明

- 模块仅在用户明确确认后执行批量互动。
- 会话只在目标应用进程内读取，不写入模块日志或持久化到模块文件。
- 好友 ID 只用于当前请求，不写入磁盘。
- 接口结构不符合预期时采用失败关闭策略，不猜测字段含义。
- 发布前应再次运行敏感信息扫描，并使用独立 release 签名构建 APK。

## 开源协议

Copyright (C) 2026 YuapXc

本项目以 [GNU General Public License v3.0 or later](LICENSE)（SPDX：
`GPL-3.0-or-later`）发布。您可以使用、修改和再分发本项目；分发原版或修改版时，须遵守
GPLv3 的许可证与对应源代码提供要求，并保留相关版权及许可证声明。

## 致谢

感谢以下开源项目及其维护者为本项目提供的 API、Hook 框架与无 Root 运行方案：

- [libxposed](https://github.com/libxposed)：提供现代 Xposed API 及相关接口定义。
- [LSPosed](https://github.com/LSPosed/LSPosed)：提供 Root 环境下的 Xposed 模块运行框架与生态基础。
- [LSPatch](https://github.com/LSPosed/LSPatch)：为免 Root APK 注入与模块加载方案提供了重要基础。
- [NPatch](https://github.com/7723mod/NPatch)：提供支持 libxposed API 102 的无 Root 运行和宿主打包方案。

「同调计划」及相关名称、图标和应用内容归其原开发者所有。本项目是独立的第三方开源工具，
与「同调计划」官方不存在隶属、授权或合作关系。

## 联系与反馈

若有相关建议，或本项目侵犯了您的合法权益，可通过
[GitHub Issues](https://github.com/YuapXc/Neural-Coherence-Tool/issues) 反馈，或联系开发者邮箱：
[yuapxc@qq.com](mailto:yuapxc@qq.com)。
