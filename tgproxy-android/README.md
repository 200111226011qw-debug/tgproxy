# tgproxy 安卓版（tgproxy_android）

桌面版 tgproxy（Python）的安卓原生移植：自动抓取代理列表 → 解析筛选（仅保留 Telegram 可用的 SOCKS5 / MTProto）→ 并发 TCP 连通性测试 → 卡片式列表展示，支持一键复制 / 分享 / 跳转 Telegram 添加代理、手动刷新、下拉刷新、自动更新间隔配置与倒计时。

- 包名 / applicationId：`com.tgproxy`
- 架构：单 Activity + Jetpack Compose + Material3 + MVVM（StateFlow 响应式直推，**无本地 HTTP 服务器、无轮询**）
- minSdk 24 / targetSdk 35 / compileSdk 35，JDK 17

## 数据源

与桌面版一致：内置 6 个免费代理源（monosans / TheSpeedX / hookzof / zevtyardt / proxifly / watchttvv）并发抓取、按 host:port 去重合并；raw.githubusercontent.com 直连失败自动回退 jsDelivr 镜像（cdn / gcore / fastly）；支持裸 `host:port` 行格式；单源 >1000 条、合并 >3000 条随机抽样；设置中的自定义数据源会追加到内置源之后。

## 构建与运行

### 方式一：Android Studio（推荐）

1. 用 **Android Studio**（Ladybug 或更新版本，自带 JBR 17）打开本目录（`tgproxy-android/`）。
2. 首次打开若提示 Gradle wrapper 不完整（本仓库未提交 `gradle/wrapper/gradle-wrapper.jar` 二进制文件），执行
   **File → Sync Project with Gradle Files**，Android Studio 会自动补全 wrapper；
   或在已安装 Gradle 8.9+ 的终端执行：

   ```bash
   gradle wrapper --gradle-version 8.9
   ```

3. 同步完成后直接 **Run ▶**（`app` 配置）安装到模拟器 / 真机。

### 方式二：命令行（需本机 JDK 17 + Android SDK）

```bash
# JDK 17 环境下
./gradlew :app:assembleDebug        # 构建 Debug APK
./gradlew :app:testDebugUnitTest    # 运行 JVM 单元测试（对拍桌面版行为）
```

> 环境要求：JDK 17；Android SDK Platform 35；`local.properties` 中配置 `sdk.dir`（不入库，Android Studio 会自动生成）。

## 工程结构

```
tgproxy-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml              # Version Catalog：全部依赖与插件版本
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.9
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle.kts                   # AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.10.01
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml        # 仅 INTERNET 权限；单 Activity；Application=.TgProxyApp
        │   ├── java/com/tgproxy/
        │   │   ├── Constants.kt           # 全部常量（超时/并发/阈值/默认值/反馈时长）
        │   │   ├── TgProxyApp.kt          # Application：初始化 AppContainer
        │   │   ├── MainActivity.kt        # 单 Activity：viewModels + setContent + onResume/onPause 转发
        │   │   ├── AppContainer.kt        # 手工 DI：OkHttpClient / ProxyFetcher / ProxyRepository / SettingsStore
        │   │   ├── model/                 # Proxy / Settings / ProxyUiState + normalize 纯函数（纯 Kotlin）
        │   │   ├── data/                  # ProxyFetcher / ProxyParser / ProxyTester / ProxyRepository / SettingsRepository
        │   │   └── ui/                    # MainViewModel / MainScreen / ProxyLinks / components / theme
        │   └── res/                       # 矢量启动图标 + 主题/颜色/字符串资源
        └── test/java/com/tgproxy/         # 7 个 JVM 单测类（对拍桌面版行为）
```

### 分层说明

| 层 | 组成 | 说明 |
|---|---|---|
| UI 层 | `MainActivity` / `MainScreen` / `ui/components` / `ui/theme` | Compose + Material3，`collectAsStateWithLifecycle()` 订阅 StateFlow |
| ViewModel 层 | `MainViewModel` | UiState + 倒计时协程 + 刷新互斥（Mutex.tryLock）+ 间隔保存 + ON_RESUME 补刷 |
| Data/Domain 层 | `ProxyParser` / `ProxyTester` / `ProxyRepository` / `model/*` / `Constants` | **纯 Kotlin 零 Android 依赖**，JVM 单测直接对拍桌面版 |
| Platform 层 | `ProxyFetcher`(OkHttp) / `SettingsRepository`(DataStore) / `AppContainer` | 面向接口（`ProxySource` / `LatencyTester` / `SettingsStore` / `TimeSource`），测试注入假件 |

## 功能说明

- **启动即刷新**：冷启动立即执行一轮「抓取 → 解析 → 并发测试 → 统计 → 排序」。
- **代理列表**：可用在前、延迟升序；状态徽章（可用/不可用）、类型徽章（SOCKS5/MTProto）、host:port 链接、延迟三档着色（<200ms 绿 / <600ms 橙 / ≥600ms 红 / 失败 灰）、地区 · ISP、入库时间；不可用行降透明度。
- **行交互**：SOCKS5 行点击 = 复制完整链接（Snackbar「已复制」+ 图标态 1.5s）；MTProto 行点击 = 跳转 Telegram（未安装则 Snackbar「未安装 Telegram，已复制链接」并自动复制）；长按菜单 = 复制 / 系统分享。
- **手动刷新 / 下拉刷新**：刷新中按钮禁用并显示「刷新中…」，列表区遮罩「正在抓取并测试代理…」；互斥语义与桌面版 `_fetching` 一致（刷新中再次触发直接忽略）。
- **间隔配置**：1~1440 分钟（默认 10），非法输入提示「请输入 1~1440 之间的整数」且不保存；保存成功「已保存，立即生效」并重置倒计时；DataStore 持久化，重启后仍在。
- **倒计时**：每秒刷新「下次自动更新：X 分 Y 秒」；到期自动刷新；退后台暂停 tick，回前台同步状态，超龄立即补刷。
- **失败降级**：抓取失败保留旧列表，红色错误条展示「抓取代理列表失败：…」，下一轮成功自动清除。
- **主题**：跟随系统 / 浅色 / 深色循环切换并持久化；浅色主题与桌面版 1:1 对齐（primary #2563EB）。
- **空态**：「暂无数据，请点击「刷新」或等待自动更新」；页脚「数据来源：\<url\>」。

## 与桌面版的对应关系简表

| 桌面版 | 安卓版 | 说明 |
|---|---|---|
| `proxy_manager.parse_proxy_text` | `data/ProxyParser.kt` | 逐行为等价移植（IPv4/端口校验、host:port 去重、excluded/dropped 计数、元数据提取） |
| `proxy_manager.fetch_proxy_text` | `data/ProxyFetcher.kt` | OkHttp，15s 超时，UA 一致；SSL 免验证兜底不移植（安卓走系统 CA Store） |
| `proxy_manager.test_tcp_latency` + ThreadPoolExecutor(20) | `data/ProxyTester.kt` | `java.net.Socket` + `Semaphore(20)`，5s 超时，保序 |
| `proxy_manager.sort_proxies` | `ProxyRepository.PROXY_COMPARATOR` | 可用在前 → 延迟升序（null 末尾）→ host → port |
| `ProxyManager`（锁 + 快照 + 互斥） | `ProxyRepository` + `MainViewModel` | 线程锁 → Mutex + 单线程化协程；`get_state()` 快照 → `StateFlow<ProxyUiState>` |
| `app.load/save/normalize_config` | `data/SettingsRepository.kt` + `model/Settings.kt` 纯函数 | DataStore 键 `interval_minutes` / `proxy_list_url` / `theme_mode`；损坏/越界回退默认 |
| `RefreshScheduler` | ViewModel 倒计时协程 | 前台每秒 tick；间隔热更新重置；后台定时（WorkManager）列为 P2 |
| `TgProxyServer` / `ApiHandler` / 端口探测 / `static/index.html` | **无（消亡）** | 安卓端无本地服务器、无端口、无 REST API；ViewModel 直接向 UI 推送 |
| `setInterval(poll, 2500)` + `visibilitychange` | `StateFlow` + `ON_RESUME` 同步 | 轮询模型被响应式数据流取代 |

## 测试

`app/src/test/java/com/tgproxy/` 下 7 个 JVM 单测类对拍桌面版行为：

| 测试类 | 覆盖 |
|---|---|
| `ProxyParserTest` | 解析/筛选/去重/计数/元数据（16 个对拍点，混合大样例三元组断言） |
| `ProxySortTest` | 排序三键 + null 延迟 + 兜底 + 入参不变 |
| `ProxyRepositoryTest` | stats 四项、excluded 合并口径、失败降级、updatedAt 格式 |
| `ProxyTesterTest` | 真实 Socket：可用/拒绝/50 条混合顺序守恒 |
| `SettingsNormalizeTest` | 间隔校正 / URL 回退 / 输入解析 / 主题模式校正 |
| `ProxyLinksTest` | `toTelegramScheme` 纯函数 |
| `MainViewModelTest` | 启动刷新 / 互斥 / 失败保留 / 间隔重置 / 到期刷新 / 补刷（虚拟时间） |
