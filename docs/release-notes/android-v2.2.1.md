## tgproxy Android v2.2.1 — 正式签名版

安卓侧**功能代码与 v2.1.0 完全相同**（`tgproxy-android/app/src` 自 v2.1.0 基线起零改动），本次只有一项变更：把发布用的 APK 从 **Debug 签名换成正式 release 签名**（密钥自持，未上架应用商店）。

| | 2.1.0（android-v2.1.0） | 2.2.1（本版） |
|---|---|---|
| 版本名 | 2.1.0 | 2.2.1 |
| versionCode | 11 | 12 |
| 签名 | Debug（`androiddebugkey`） | 正式 release 签名（自持密钥；证书 SHA-256 摘要见下） |
| 功能代码 | — | 与 v2.1.0 相同，未改 |

### ⚠️ 安装前必读：必须先卸载旧版

新包与旧 Debug 包 **applicationId 相同（`com.tgproxy`）但签名不同**，Android 不允许直接覆盖安装。
**请先卸载 v2.1.0（Debug 版）再装本版**；卸载会清掉应用私有目录里的 known_good 沉淀与连败拉黑表。

### 为什么与桌面版同为 2.2.1

桌面 v2.2.1 的三项改动（商店版 Telegram 唤起假阴性修复、状态文件收进 `data/`、内置窗口体积限制）**都只涉及桌面端**，安卓侧不需要改。本版沿用桌面版本号，方便两端对应。

### 测试

- 安卓 JVM 单测 **125 例全绿**（15 个套件）
- 桌面 pytest **93 passed**

（以上为本次发布时实测，非引用旧记录。）

### 已知边界

- 未上架应用商店，签名密钥**自持**：密钥丢失将无法再更新本应用
- 升级必须先卸载旧版，应用私有数据不迁移
- 两端 UI 未做视觉验收
- 安卓无当前版本的真机计时基线

### 校验值

```
tgproxy-android-v2.2.1.apk  11874283 B
sha256  b5939c9752a817824e50d071c8cf83d138cb574bd13775a2500fade2a3d391e2
```

### 验签信息

```
Signer #1 certificate DN: CN=TGProxy, OU=Personal, O=kkf, C=CN
Signer #1 certificate SHA-256 digest: 6b022d5e164a3050f99070e0a2cbe57d4c2476b35737fd61ac664bfc75196031
```