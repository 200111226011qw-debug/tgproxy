# 复核方法论（内部文档）

> **范围**：面向项目维护者，记录「怎么核一个声称」的方法。**不随发布包分发**——桌面发布 zip 仅打包 `tgproxy/` 下 5 个文件，本文档在仓库根 `docs/internal/` 下，用户下载的包里没有它。
> **来源**：2026-10-25 对桌面版 v1.5.0→v1.7.3 的五轮复核（36 条发现全部闭环）。
> **配套**：环境侧信息（构建、网络、发布管线）只对单台机器有效，不入库。

---

## 零、核心原则

> **「改了」和「改对了」是两件事。**

任何「已修复」的声称，都必须落到**可独立复算的证据**上：代码行号、字节哈希、测试输出、API 返回。不采信自述，也不采信渲染后的页面。

---

## 一、三条流程规则

### 规则 1：stat 对照

发布说明里列的每一项，其涉及文件必须出现在 `git show --stat --oneline HEAD` 里。没出现就不许写。

```bash
git show --stat --oneline HEAD
```

> **由来**：v1.7.2 提交只含 1 个文件，发布说明却列了 4 项，其中 2 项结构上不可能发生（README 与发布说明编辑根本不在 diff 里）。

### 规则 2：文档自检两步

````bash
# ① 重复条目扫描（按文档结构选模式，两种都要跑；只查一种会"假通过"）
awk '/^[[:space:]]*```/{f=!f;next} !f && /^#/{print}' <file> | sort | uniq -d   # 标题（跳过围栏）
grep "^- " <file> | sort | uniq -d                                            # 列表项

# ② 文档里每个阈值/触发条件，与代码常量逐一对照
grep -n "连续 3 轮\|80%\|占比" <file>
````

> **由来（①）**：桌面 README 曾把重复的「爬虫模式」删掉、又新增重复的「known_good 沉淀」——标题/列表项级重复扫描能拦住。
> **注意假通过**：曾用 `grep -o "^- \*\*[^*]*\*\*"` 查一份 20 个标题、**0 个加粗列表项**的文档，返回空是**没有可匹配项**（vacuous pass），不是「无重复」。**模式必须匹配该文档实际使用的结构**，否则检查形同未做。
> **注意假重复**：朴素 `grep "^#"` 会把代码围栏内以 `#` 开头的 shell 注释当成标题——两段代码块各写一行相同的 `# 示例` 就会报出并不存在的「标题重复」。故标题扫描用 `awk` 跳过围栏（见上）。**检查本身也要做注入验证**：造一个含重复标题、重复列表项、围栏内重复注释的样本，确认该报的报、不该报的不报。
> **由来（②）**：`tgproxy/README.md:15` 曾把「0 产出占比过高」（**保护**的触发词）写成了「停用」的触发词，方向正好反——读者以为「零产出源多到一定程度会被批量换掉」，实际是「谁都不换」。这类错误 ① 拦不住，只能靠阈值对照。

### 规则 3：线上内容只认 API

**判定 GitHub Release 正文是否已改，只认 API（带 no-cache）或仓库文件，不认 HTML 页面。**

```powershell
$h=@{'Cache-Control'='no-cache';'Pragma'='no-cache';'User-Agent'='review'}
$r=Invoke-RestMethod -Uri 'https://api.github.com/repos/<owner>/<repo>/releases/tags/<tag>' -Headers $h
$r.published_at   # 发布时刻
$r.updated_at     # 最后编辑时刻 —— 晚于 published_at 即证明被编辑过
$r.body.Contains('旧句')   # 断言旧句已不在
```

> **由来**：抓 `releases/tag/v1.7.1` 的 HTML 命中缓存快照，误判「正文没改」。API 显示 `updated_at` 晚于 `published_at` 16 分钟，订正确实做了。**此规则双向适用**——评审方自己也会犯。

---

## 二、取证技术

### 2.1 字节级同一性

```powershell
(Get-FileHash <file> -Algorithm SHA256).Hash.ToLower()
```

比对三处：本地构建产物、Release 资产、工作区文件。**同一版本号下 sha256 相同 = 未重建**（据此判断「exe 是否真重打包」）。

> 注意：文档/测试变更后 exe 未重建是**正确决定**（桌面代码没动、exe 不打包 README），问题只在说明措辞。别把「没重建」本身当缺陷。

### 2.2 PyInstaller onefile 解析（核 exe 内嵌代码）

三个必踩的坑，按顺序：

| 坑 | 现象 | 正解 |
|---|---|---|
| cookie 偏移 | `struct.unpack('!8sIIII')` 从 `MEI` 之后开始 → 魔数读出乱码 | 24 字节结构体**首字段就是魔数**，从 `MEI` 起点解包 |
| TOC 解析 | 按 18 字节定长读 → 得到乱码 | PyInstaller 6 的 TOC 是 **marshal 序列化**的列表 |
| PYZ 定位 | 在归档条目里找 `PYZ.pyz` → 解压失败 | PYZ 在 CArchive 内**可能未压缩**，直接搜 `b'PYZ\x00'` |

模块 blob 的额外一坑：**PyInstaller 已剥离 pyc 头**，`marshal.loads` 要从 `\xe3`（TYPE_CODE）起，不能跳 16 字节。

拿到 code object 后：

```python
names = set(); stack = [code]
while stack:
    c = stack.pop(); names.update(c.co_names)
    stack.extend([k for k in c.co_consts if hasattr(k, 'co_code')])
"RLock" in names            # 区分新旧版本的特征名
dis.get_instructions(fn)    # 找 COMPARE_OP 与 LOAD_CONST 2/4，确认分支存在
```

> 这招能把「产物是真的」从自述变成**可独立复算的事实**。

### 2.3 构建副本一致性

逐文件比对 `app/src` 与 HEAD。**注意 CRLF vs LF**，比对前归一化：

```python
hashlib.sha256(b.replace(b'\r\n', b'\n')).hexdigest()
```

### 2.4 边界真值表

对状态机类逻辑枚举参数组合，各连续 N 轮，读**落盘状态**（不是内存态）断言：

```
源数 1~5 × 零产出数 0~n，各跑 3 轮，读 source_health.json 的 parked 字段
```

> 这招抓出过「4 源 3 零（75%）本应保护却换掉 3 个源」——单点测试看不出来。

### 2.5 跨文件一致性

同一事实在多份 README 里是否一致（仓库根 `README.md` vs `tgproxy/README.md`）。**打包内那份才是用户真正读到的**，最容易漏。

---

## 三、误判案例（评审方自己踩的，留着当警示）

### 案例 1：缓存快照误判

用 HTML 页判定「发布说明没改」，实际 16 分钟前就改了。**教训**：可变内容一律走 API，并比对 `updated_at`。

### 案例 2：把自己的交付物记错

断言「我给的用例片段缺某行」，实际报告里就有。**教训**：指控对方「照抄错了」之前，先 grep 自己落盘的那份，报行号。

### 案例 3：替换残留

大段替换后出现重复短语，多为「目标句前缀未连带删除」。

- `uniq -d` 与阈值对照**都拦不住**
- 处置：**改完把整句读一遍**，不值得为此加机械规则（成本 > 收益）

---

## 四、表述边界（别把「对齐」说大）

核过的部分才能说对齐，且要分端说：

> **桌面侧**：代码 / 文档 / 测试 / 产物四者对齐（有哈希、测试输出、API 证据）。
> **安卓侧**：代码与某版一致（有 JUnit XML），但发布形态为 debug 签名、部分断言未补 → **不说四者对齐**。

**规则**：没有构建产物支撑的那一端，不参与「对齐」表述。评审方无法核实的部分要明确标注「未能核实」，而不是含混带过。

---

## 五、一页速查

````bash
git show --stat --oneline HEAD                        # 规则 1
awk '/^[[:space:]]*```/{f=!f;next} !f && /^#/{print}' <f> | sort | uniq -d   # 规则 2-① 标题
grep "^- " <f> | sort | uniq -d                       # 规则 2-① 列表项
grep -n "<阈值>\|<触发词>" <f>                          # 规则 2-②
curl / Invoke-RestMethod api.github.com/... --no-cache # 规则 3
sha256sum <file>                                       # 2.1
python -m pytest <tests> -q                            # 测试真跑
````

四条自检能拦住的：说了没做（1）、做了但写错（2）、判错了以为对方没做（3）。
拦不住的：替换残留（读整句）、产物未重建（看哈希）、单点测试漏边界（真值表）。
**规则本身要注入验证**：该报的报、不该报的不报——只跑一次「结果为空」证明不了检查有效。
