# Neural Coherence Tool

基于 [libxposed API 102](https://libxposed.github.io/api/index.html) 的 LSPosed 模块，
为目标应用的「同调网络」页面提供好友状态扫描与顺序互动工具。

> 本项目仅适用于你有权测试和使用的账号及应用环境。批量操作应遵守目标服务的使用规则，
> 并合理控制请求频率。

## 功能

### 好友状态扫描

- 分页读取完整好友列表，不依赖页面是否已滚动加载。
- 统计无色、蓝色、绿色和橙色四种当日互动状态。
- 扫描操作只读取数据，不发送好友互动。
- 严格校验响应结构、状态范围和好友 ID；接口不兼容时立即停止。

### 一键互动

- 无色状态发送 `ping`，绿色状态发送 `pong`。
- 蓝色和橙色表示当日已完成对应操作，自动跳过。
- 请求按顺序执行，间隔在 500 至 1000 毫秒之间随机选择。
- 任一请求失败后立即停止，不继续处理后续好友。
- 运行期间可通过面板手动停止。

### 页面面板

- 控制面板显示在「同调网络」标题区域。
- 提供扫描、互动、停止和实时进度显示。
- 离开对应底部标签时隐藏面板。
- 滑动和系统手势不会被误判为底部标签点击。
- 打开「同调记录」或「好友申请」弹窗时临时隐藏面板，返回后恢复。
- Activity 重建后恢复当前任务状态和进度。

### 可靠性保护

- 批量任务启动后的 10 分钟内阻止再次批量执行，扫描功能不受影响。
- 开始任务前检查当前网络是否可用。
- 区分无网络、DNS/连接失败、超时和 TLS 安全连接错误。
- 不在日志中输出登录会话、完整好友 ID、请求签名或服务器响应正文。
- 不使用固定 Dart AOT 函数偏移，降低目标应用重新编译造成的失效风险。

## 兼容性

- LSPosed/libxposed API：102
- Android：最低 API 29
- 架构：模块本身不包含 native 库
- 作用域：`com.linktech.arkradar`

模块通过 Flutter Activity 注入面板，并通过目标应用现有登录会话访问其接口。若目标应用修改
接口路径、鉴权方式、字段名称或底部导航结构，模块会停止相关操作或需要重新适配。

## 项目结构

```text
.
|-- app/
|   |-- build.gradle.kts                 # Android 模块配置与本地构建参数
|   `-- src/main/
|       |-- AndroidManifest.xml          # Android 应用清单
|       |-- java/io/github/neuralcoherence/probe/
|       |   `-- NeuralCoherenceModule.java # libxposed 入口、面板、扫描与互动逻辑
|       |-- res/values/strings.xml       # 模块名称和说明
|       `-- resources/META-INF/xposed/
|           |-- java_init.list           # libxposed Java 入口
|           |-- module.prop              # API 版本及模块属性
|           `-- scope.list               # 默认目标作用域
|-- build.gradle.kts                     # Android Gradle Plugin 配置
|-- gradle/wrapper/                      # Gradle 8.9 Wrapper
|-- gradlew / gradlew.bat                # 跨平台构建入口
|-- gradle.properties                    # Gradle 构建选项
|-- settings.gradle.kts                  # 仓库与模块声明
`-- README.md
```

## 本地配置

请求签名配置不会提交到版本库。开发者需要在项目根目录的 `local.properties` 中添加：

```properties
SYNC_SIGNING_SALT=<your-local-value>
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

## 安全说明

- 模块仅在用户明确确认后执行批量互动。
- 会话只在目标应用进程内读取，不写入模块日志或持久化到模块文件。
- 好友 ID 只用于当前请求，不写入磁盘。
- 接口结构不符合预期时采用失败关闭策略，不猜测字段含义。
- 发布前应再次运行敏感信息扫描，并使用独立 release 签名构建 APK。
