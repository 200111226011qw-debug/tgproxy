# tgproxy 安卓版（tgproxy_android）

桌面版 tgproxy（Python）的安卓原生移植（v2.0.0 起与桌面版测试口径对齐；v2.1.0 列表优先布局重做 + 深色仪表盘视觉 + 连败拉黑加速）：自动抓取代理列表 → 解析筛选（仅保留 Telegram 可用的 SOCKS5 / MTProto）→ 连败拉黑跳过（连续 3 轮不通的节点 48 小时内不再测试，省时）→ TCP 预筛（128 并发）→ **TG 实测双闸**（经代理 CONNECT Telegram 数据中心 + 全链路访问 desktop.telegram.org，TLS+HTTP）→ **1000ms 延迟门槛** → 节点卡片列表（首屏即列表，v2rayNG 式「顶栏 + 卡片 + FAB」），每张卡片带渐变「使用」主按钮一键唤起 Telegram 添加代理，另支持复制 / 分享 / 长按菜单、下拉刷新、右下角 FAB 刷新、自动更新间隔与倒计时、分阶段进度与上轮耗时摘要、known_good 沉淀（启动即展示上轮可用节点，可开关）、0 节点时的全链路透明解释卡。

- 包名 / applicationId：`com.tgproxy`
- 架构：单 Activity + Jetpack Compose + Material3 + MVVM（StateFlow 响应式直推，**无本地 HTTP 服务器、无轮询**）
- minSdk 24 / targetSdk 35 / compileSdk 35，JDK 17

## 数据源

与桌面版一致：内置 5 个免费代理源（hideip.me / proxyscrape / monosans / TheSpeedX / watchttvv，2026-10-25 统一体检精选）并发抓取、按 host:port 去重合并；raw.githubusercontent.com 直连失败自动回退 jsDelivr 镜像（cdn / gcore / fastly）；支持裸 `host:port` 行格式；单源 >1000 条、合并 >3000 条随机抽样；设置中的自定义数据源会追加到内置源之后。

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
./gradlew :app:assembleRelease      # 构建 Release APK（没有密钥材料时产物为未签名）
```

> 环境要求：JDK 17；Android SDK Platform 35；`local.properties` 中配置 `sdk.dir`（不入库，Android Studio 会自动生成）。

### ⚠️ 必须从 ASCII 路径构建（中文路径下测试跑不起来）

工程所在路径含中文（本机用户名是中文）时，**Gradle 的测试 worker JVM 起不来**：
它把命令行写进 `@argfile`，而 JDK 17 按系统 ANSI 代码页（GBK）解码 `@argfile`，
路径里的中文被读成乱码 → worker 连引导类 `worker...GradleWorkerMain` 都加载不到，
表现为 `ClassNotFoundException` 或 `Gradle Test Executor N finished with non-zero exit value 1`。

> 这不是测试失败，是 worker 根本没起来。**每次失败都是 11~15 个套件一起报 `ClassNotFoundException`**。

`assembleRelease` 不受影响（不经过 test worker），所以会出现「APK 出来了但状态是 FAILED」的错觉。

**解法：让 Gradle 看到纯 ASCII 路径。** 下面示例是**本机**布局（换成任意纯 ASCII 路径同理），本机已建好 junction：

```powershell
# 工程与 Gradle 环境都指向 ASCII 路径
$env:JAVA_HOME='C:\btg\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME='C:\btg\sdk'
$env:GRADLE_USER_HOME='C:\btg\gradle-home'
& 'C:\btg\gradle\gradle-8.9\bin\gradle.bat' -p 'C:\btg\tgproxy-live' `
  --no-daemon --console=plain --init-script 'C:\btg\mirror.init.gradle' :app:testDebugUnitTest
```

`C:\btg\tgproxy-live` 是指向本工程目录的 junction（`New-Item -ItemType Junction`），
构建产物仍然写回工程目录本身。校验基线：**125 例全绿**（15 个套件）。

> ⚠️ **junction 必须用 `-p` 传进去，`cd` 进去不行。**
> `cd C:\btg\tgproxy-live && gradle ...` 会让 Gradle 把 junction 规范化回真实路径
> （`projectDir` 打印出来仍是 `C:\Users\黄冬\...`），`@argfile` 里照样是中文，
> 11~15 个套件一起 `initializationError`。只有 `gradle -p C:\btg\tgproxy-live`
> 才会保留 ASCII 前缀。2026-10-28 两种写法都实测过：`-p` → 125 全绿，`cd` → 全挂。

### 正式签名构建（release keystore）

签名材料**不入库**：密钥文件放仓库外，`keystore.properties` 已被 `.gitignore` 忽略，
样例见 [`keystore.properties.sample`](keystore.properties.sample)。取值优先级为**环境变量 > `keystore.properties`**：

| 环境变量 | 对应字段 |
|---|---|
| `TGPROXY_KEYSTORE` | `storeFile`（密钥文件绝对路径） |
| `TGPROXY_STORE_PASSWORD` | `storePassword` |
| `TGPROXY_KEY_ALIAS` | `keyAlias` |
| `TGPROXY_KEY_PASSWORD` | `keyPassword` |

两者都没有时，`release` 构建类型保持**未签名**（产出 `app-release-unsigned.apk`），构建不会失败。

> **密钥丢了就永久无法再更新这个应用**（未上架应用商店，没有第三方托管签名）。生成后请离线备份一份。
> 正式签名包与历史 Debug 包**签名不同、applicationId 相同**，老用户必须先卸载旧版再安装。

### 关于 APK 的字节可复现性

安卓 APK **不能字节复现**，但这与构建配置无关：

- ZIP 载荷（129 个条目）、中央目录，以及 APK Signing Block 里的 v2 签名对，
  两次构建**逐字节相同**（AGP 已把条目时间戳归一化为 `1981-01-01 01:01:02`）
- 差异只在签名块内一对 ID 为 `0x504b4453` 的高熵数据上（约 5.4 KB），
  它位于 v2 签名对之后、verity padding 之前，**不在 v2 签名的覆盖范围内**，每次签名都不同

因此校验**只能用发布物本身**：从 Releases 下载后重算 sha256，与 Release 正文里的值比对。
用本地重建产物去比对会得到「不一致」的假警报。

> 仓库根 `.gitignore` 里「spec 入库以保证从 tag 可重建 exe」只承诺**桌面版 exe**，不涉及 APK。

### 发布一次安卓 release（步骤）

> 下面七条是 2026-10-28 发布 v2.2.1 时用真金白银换来的，漏一条就会有人再踩一次。

**① 生成 keystore 之后立刻备份 —— 这是第 0 步，不是最后一步**

包一旦发出去就没有退路：密钥丢了 = 这个应用**永远无法再更新**（未上架应用商店，
没有第三方托管签名）。换 `applicationId` 等于换一个 App，老用户卸载重装、数据全丢。

```bat
mkdir "%USERPROFILE%\.android"
"%JAVA_HOME%\bin\keytool.exe" -genkeypair -v ^
  -keystore "%USERPROFILE%\.android\tgproxy-release.jks" ^
  -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 ^
  -alias tgproxy-release ^
  -dname "CN=TGProxy, OU=Personal, O=kkf, C=CN"
```

> 上面的**参数是建议值**（PKCS12 / RSA 4096 / 10000 天）。硬性要求只有两条：
> **密钥文件放在仓库外**、**口令不进命令行**。

口令由 keytool **交互式询问**（不传 `-storepass`，口令就不会进命令行历史）。
备份到**两个不同的地方**；备份完把明文口令文件删掉，口令进密码管理器。
想让「备份存在」也可验证，就把密钥文件的 sha256 记下来 —— 任何一份副本算一下
哈希对上就是同一把钥匙（证书指纹则换容器也不变，用来判断是不是同一把钥匙）。

**② `storeFile` 必须是绝对路径**

`build.gradle.kts` 里是 `file(storePath)`，相对路径会按 `app/` 解析，指向错误位置。

**③ 两个口令都要填**

只填 `storePassword` 会在**打包阶段**才失败：
`SigningConfig "release" is missing required property "keyPassword"` ——
编译全白做一遍，报错还不指向 `keystore.properties`。

**④ 构建必须用 `-p <ASCII junction>`，`cd` 进去无效**

见上面「必须从 ASCII 路径构建」一节：`cd` 进去 Gradle 会把 junction 规范化回
真实路径，`@argfile` 里又是中文，十几套件一起 `initializationError`。

**⑤ tag 用 `git tag -a` 先建好再推**

不要让 Release API 在 tag 不存在时隐式创建 —— 那会得到一个**轻量 tag**，
而且指向「建 Release 那一刻的 main」，不是产出 APK 的那个 commit，溯源就断了。

```bash
git tag -a android-v2.2.1 -m "tgproxy Android android-v2.2.1 正式签名版" <产出 APK 的 commit>
git push <ssh-url> refs/tags/android-v2.2.1
```

**⑥ digest 必须现查 API，不能从历史消息或旧文档里搬**

跨版本引用的数字（digest / 大小 / 行数 / 测试例数）在写进任何结论前都要**现查一次**：
上下文里的旧值不是缓存，是陷阱 —— 它长得和正确答案一模一样。
（真实案例：把 v2.2.0 的 `e01385de…` 写成了 v2.2.1 的，而 v2.2.1 实际是 `1803f409…`。）

连**行数、字节数**这类"随手可数"的量也一样 —— **用同一条命令量，别用记忆里的数**；
报之前先统一口径（`wc -l` 数的是换行符；用 `split` 切会把末尾空串多算一行）。

**⑦ 校验只能下载发布物重算**

APK 不可字节复现（见上一节），拿本地重建产物去比对会得到「发布坏了」的**假警报**。
正确做法：从 Releases 下载 → 重算 sha256 → 与 Release 正文里的值比对，
再与 GitHub 返回的 asset `digest` 比对，**三方一致**才算过。

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
        │   │   ├── Constants.kt           # 全部常量（超时/并发/TG 双闸目标与门槛/默认值/反馈时长）
        │   │   ├── TgProxyApp.kt          # Application：初始化 AppContainer
        │   │   ├── MainActivity.kt        # 单 Activity：viewModels + setContent + onResume/onPause 转发
        │   │   ├── AppContainer.kt        # 手工 DI：OkHttpClient / ProxyFetcher / ProxyRepository / SettingsStore
        │   │   ├── model/                 # Proxy(Stats 六项/TgVerdict) / Settings / ProxyUiState / RefreshProgress + normalize 纯函数
        │   │   ├── data/                  # ProxyFetcher / ProxyParser / ProxyTester(预筛) / TelegramGate(TG 双闸) / KnownGoodStore / KnownBadStore(连败拉黑) / ProxyRepository / SettingsRepository
        │   │   └── ui/                    # MainViewModel / MainScreen / ProxyLinks / components / theme
        │   └── res/                       # 矢量启动图标 + 主题/颜色/字符串资源
        └── test/java/com/tgproxy/         # 11 个 JVM 单测类（对拍桌面版行为）
```

### 分层说明

| 层 | 组成 | 说明 |
|---|---|---|
| UI 层 | `MainActivity` / `MainScreen` / `ui/components` / `ui/theme` | Compose + Material3，`collectAsStateWithLifecycle()` 订阅 StateFlow |
| ViewModel 层 | `MainViewModel` | UiState + 倒计时协程 + 刷新互斥（Mutex.tryLock）+ 间隔保存 + ON_RESUME 补刷 |
| Data/Domain 层 | `ProxyParser` / `ProxyTester` / `ProxyRepository` / `model/*` / `Constants` | **纯 Kotlin 零 Android 依赖**，JVM 单测直接对拍桌面版 |
| Platform 层 | `ProxyFetcher`(OkHttp) / `SettingsRepository`(DataStore) / `AppContainer` | 面向接口（`ProxySource` / `LatencyTester` / `SettingsStore` / `TimeSource`），测试注入假件 |

## 功能说明

- **启动即刷新 + known_good 先行展示**：冷启动立即执行一轮「抓取 → 解析 → TCP 预筛 → TG 实测 → 门槛过滤 → 统计 → 排序」；known_good 开关开启且有沉淀时，列表先行展示上轮验证通过的节点（半透明 + 「未测」胶囊标注，待本轮复测），无需等待刷新。
- **TG 实测双闸（v2.0.0，与桌面版逐字节对齐）**：第一关经 SOCKS5 代理 CONNECT Telegram 数据中心 149.154.167.51:443（无认证握手 05 01 00 → CONNECT，IPv4 ATYP=1 / 域名 ATYP=3，往返毫秒即节点延迟）；第二关经代理 CONNECT desktop.telegram.org + TLS 证书校验 + HTTP 状态行（GET，验证全链路可用无劫持）；两关串行共享单节点 20s 总预算（单关 8s）；仅 SOCKS5 参与，MTProto 记「未验证」；并发 96。
- **1000ms 延迟门槛**：仅保留经代理到 TG 数据中心往返 ≤1000ms 的节点（固定值，与桌面版默认一致），超门槛计入「TG 未过」。
- **分阶段进度与耗时摘要（v2.0.0；v2.1.0 并入列表头部状态条）**：刷新中显示「抓取数据源 i/N → TCP 预筛 i/N → TG 实测 i/N」+ 进度条；空闲时显示「上轮测试 N 个节点，保留 X 个，耗时 Y · 下次更新 Z」——刷新完成有据可查。
- **列表优先布局（v2.1.0）**：首屏 = 节点列表（LazyColumn + 下拉刷新，状态条/统计/错误条/空态作为列表首项随内容滚动）；顶栏只留 标题 + 更新时间/倒计时 + 设置入口；刷新按钮为右下角 FAB（刷新中转圈）；设置（间隔 / known_good / 外观 / 数据源说明）收进底部弹层。
- **代理卡片**：左侧 4dp 延迟色条（<1000 绿 / <3000 橙 / 其余红 / 未测 灰，扫视即知）+ host:port（等宽粗体）+ 类型 · 地区 · ISP + 延迟胶囊（数值冗余编码，色盲友好）+ **「使用」主按钮**（48dp 触控，一键唤起 Telegram「添加代理」）+ 复制小按钮；长按 = 复制/分享菜单；known_good 先行展示的未复测条目半透明并标注「未复测」。
- **行交互**：整卡点击直接唤起 Telegram「添加代理」窗口（SOCKS5 用 `tg://socks` 深链、MTProto 用 `tg://proxy`，不经过网页），未安装 Telegram 时降级为复制链接 + Snackbar 提示；长按或行内按钮 = 复制（图标态 1.5s）/ 系统分享。
- **手动刷新 / 下拉刷新**：刷新中按钮禁用并显示「刷新中…」+ 进度卡；互斥语义与桌面版 `_fetching` 一致（刷新中再次触发直接忽略）。
- **间隔配置**：1~1440 分钟（默认 10），非法输入提示「请输入 1~1440 之间的整数」且不保存；保存成功「已保存，立即生效」并重置倒计时；DataStore 持久化，重启后仍在。
- **known_good 沉淀开关（v2.0.0）**：控制卡内「known_good 沉淀」开关；关闭后沉淀文件保留但不再读取/写入（与桌面版 v1.8.0 开关语义一致）。
- **倒计时**：每秒刷新「下次自动更新：X 分 Y 秒」；到期自动刷新；退后台暂停 tick，回前台同步状态，超龄立即补刷。
- **失败降级**：抓取失败保留旧列表，红色错误条展示「抓取代理列表失败：…」，下一轮成功自动清除；本轮 0 节点达标但已有列表时同样保留旧列表并提示门槛。
- **六项统计（v2.1.0 拆分口径）+ 连败拉黑位（v2.1.0）**：仪表盘统计卡五段（TG 未过 / 超门槛 / TCP 不通 / **已拉黑** / 已排除）+ Hero 大卡上的保留数；口径契约：available+unverified+tgFailed+overThreshold+dropped = tested（桌面版 tg_failed = tgFailed + overThreshold，含超慢口径不变）；blacklisted 独立于 tested（跳过的节点不计入本轮测试总数）。
- **连续失败拉黑（v2.1.0，与桌面版 v1.9.0 对齐）**：TCP 预筛不通或 TG 两关未过 → 连败 +1；两关通过（含超门槛）→ 清零；连败 3 轮自动拉黑 48 小时，期间该节点直接跳过测试（省时）；48 小时后自动解封重测（免费池 IP 会轮换/复活）；拉黑表持久化到应用私有目录 `known_bad.json`，超 10000 条按最久未失败淘汰。
- **仪表盘视觉（v2.1.0，参考深色数据仪表盘风格）**：深藏青背景（#0D1526 系）+ 蓝青渐变 Hero 状态大卡（可用节点大数字 + 最优延迟 + 上轮耗时 + 下次更新倒计时，刷新中变为阶段进度）+ 五段统计卡 + 节点卡渐变「使用」按钮 + 圆底复制按钮；浅色主题同构。
- **0 节点透明解释卡（v2.1.0）**：列表为空时展示本轮全链路数据（测试 N / TCP 通过 M / TG 两关通过 K / 其中超门槛 J），并说明「有节点但超门槛不展示（宁缺毋滥）」或「本轮免费池无节点过两关」——工具在工作，不是坏了。
- **主题与图标**：跟随系统 / 浅色 / 深色循环切换并持久化（设置弹层内）；深色主题为 Telegram 品牌配色（#17212B 背景 + #2AABEE 主色）；自适应应用图标 = 深底 + TG 蓝纸飞机 + 白速度线（#17212B / #2AABEE）。
- **空态**：见上方「0 节点透明解释卡」；页脚「TG 两关实测 · 仅保留 ≤1000ms · 点「使用」一键连 Telegram」。
- **说明**：源自动体检换源与爬虫模式仍为桌面版功能；安卓版数据源列表与桌面版保持一致。

## 与桌面版的对应关系简表

| 桌面版 | 安卓版 | 说明 |
|---|---|---|
| `proxy_manager.parse_proxy_text` | `data/ProxyParser.kt` | 逐行为等价移植（IPv4/端口校验、host:port 去重、excluded/dropped 计数、元数据提取） |
| `proxy_manager.fetch_proxy_text` | `data/ProxyFetcher.kt` | OkHttp，15s 超时，UA 一致；SSL 免验证兜底不移植（安卓走系统 CA Store） |
| `proxy_manager.test_tcp_latency` + ThreadPoolExecutor(150) | `data/ProxyTester.kt` | TCP 预筛：`java.net.Socket` + `Semaphore(128)`，5s 超时，保序，逐条进度上报 |
| `proxy_manager._socks5_connect` / `test_telegram_via_socks5` / `test_telegram_web_via_socks5` | `data/TelegramGate.kt` | SOCKS5 握手字节序逐项一致（05 01 00 / ATYP 1·3·4 / 状态行校验）；两关串行共享 20s 总预算，单关 8s；并发 96 |
| `known_good.json` 读/写/合并 + 开关 | `data/KnownGoodStore.kt` | 应用私有目录 known_good.json；开关关闭不读不写；延迟/状态不落盘（下轮一律重测） |
| `known_bad.json` 连败拉黑（桌面 v1.9.0） | `data/KnownBadStore.kt` | 连败 3 轮跳过测试、48 小时解封、超量淘汰；时钟注入可测 |
| `proxy_manager.sort_proxies` | `ProxyRepository.PROXY_COMPARATOR` | 可用在前 → 延迟升序（null 末尾）→ host → port |
| `ProxyManager`（锁 + 快照 + 互斥 + 分阶段管线） | `ProxyRepository` + `MainViewModel` | 线程锁 → Mutex + 单线程化协程；`get_state()` 快照 → `StateFlow<ProxyUiState>`；阶段进度（抓取/TCP/TG）→ `RefreshProgress` 直推 |
| `app.load/save/normalize_config` | `data/SettingsRepository.kt` + `model/Settings.kt` 纯函数 | DataStore 键 `interval_minutes` / `proxy_list_url` / `theme_mode`；损坏/越界回退默认 |
| `RefreshScheduler` | ViewModel 倒计时协程 | 前台每秒 tick；间隔热更新重置；后台定时（WorkManager）列为 P2 |
| `TgProxyServer` / `ApiHandler` / 端口探测 / `static/index.html` | **无（消亡）** | 安卓端无本地服务器、无端口、无 REST API；ViewModel 直接向 UI 推送 |
| `setInterval(poll, 2500)` + `visibilitychange` | `StateFlow` + `ON_RESUME` 同步 | 轮询模型被响应式数据流取代 |

## 测试

`app/src/test/java/com/tgproxy/` 下 11 个 JVM 单测类对拍桌面版行为（debug/release 双变体共 170 例全绿）：

| 测试类 | 覆盖 |
|---|---|
| `ProxyParserTest` | 解析/筛选/去重/计数/元数据（16 个对拍点，混合大样例三元组断言） |
| `ProxySortTest` | 排序三键 + null 延迟 + 兜底 + 入参不变 |
| `ProxyRepositoryTest` | 六项统计口径（和=tested，超门槛单列）、TG 门槛剔除、TCP 不通不进 TG、进度三阶段序列、known_good 开关两端行为、失败降级、updatedAt 格式、连败拉黑四场景（三轮拉黑跳过 / 通过清零 / 超门槛清零 / 48 小时解封） |
| `TelegramGateTest` | SOCKS5 握手字节序（方法协商/CONNECT/ATYP 1·3）、TLS GET 请求（Host/UA/Connection）、HTTP 状态行校验、握手被拒/非零错误码/EOF/连接失败、剩余预算不足 1s 放弃第二关 |
| `KnownGoodStoreTest` | 读写往返、延迟状态不落盘、开关关闭不读不写、损坏回退、坏条目跳过、空列表不落盘 |
| `KnownBadStoreTest` | 连败达标拉黑、通过清零、落盘回读、48 小时解封、开关关闭不读不写、损坏容忍、超容量淘汰 |
| `ProxyTesterTest` | 真实 Socket：可用/拒绝/50 条混合顺序守恒 |
| `SettingsNormalizeTest` | 间隔校正 / URL 回退 / 输入解析 / 主题模式校正 |
| `ProxyLinksTest` | `toTelegramScheme` 纯函数 |
| `MainViewModelTest` | 启动刷新 / 互斥 / 失败保留 / 间隔重置 / 到期刷新 / 补刷 / 首轮 0 达标清空 / 已有列表时 0 达标保留旧列表 / known_good 启动先行展示 / 开关持久化（虚拟时间） |
