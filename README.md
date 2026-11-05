# 红果免费短剧广告自动跳过 (AutoSkip Ad)

原生 Android App(Kotlin + Jetpack Compose),通过无障碍服务在**红果免费短剧**内识别「关键词节点被广告盖住」并自动上滑跳过。
**零第三方运行时依赖,纯本地 UI 树解析,不联网、不 OCR、不自动点按钮。**

---

## 功能

- **自动上滑跳过**:默认每秒扫描一次当前无障碍窗口,播放页的关键词(默认「选集」)被广告盖住就上滑
- **严格判定**:关键词节点存在但不可见,或顶部识别到「第X集」时,才动手;首页/搜索页/榜单页没有这些播放页特征,不会被误滑。可在主界面关回旧的宽松规则
- **「第X集」兜底**:顶部左侧 `第1集`–`第999集` 作为第二播放页信号,广告把整棵树替换掉时仍能识别
- **误滑换集保护**:上滑前后对比集数,集数变了立即停止重试并锁定 20 秒,不再连续滑到下一集
- **只作用于红果免费短剧**:`com.phoenix.read`,config 层的 `packageNames` 已把事件源限死在这个包,其他 App 完全收不到事件
- **不抢用户操作**:用户手动翻页后 3 秒内不动作
- **只处理"新出现"的判定**:同一状态绝不重复滑
- **广告有时限也能跳**:上滑后校验一次,没跳掉就继续滑,重试前要连续三次复核,最多等 60 秒
- **可配置规则**:判定关键词(逗号分隔)、扫描间隔(300–5000ms)、严格模式,主界面即时保存
- **高级规则**:支持类易语言的 `如果 / 否则如果 / 否则 / 结束如果` 与 `如果真`,可组合 `且/或/非` 和数值比较
- **悬浮球 + 管理面板**:短按打开管理面板,长按快速开关总开关;球可拖动换位置,越界会自动夹回屏内
- **通知栏快捷操作**:保活通知上直接「开启/关闭」总开关或「上滑一次」
- **运行统计**:持久化累计「成功跳过 / 上滑」次数,可重置
- **运行日志与导出**:App 内实时日志区保留最近 120 条,支持一键导出为文本并经系统分享面板发出
- **顶部提示条**:上滑时短暂显示「已上滑 ✓」,约 2.5 秒后自动消失

---

## 判定规则

核心在 `AdDetector.kt` + `AdRule.kt`:

```kotlin
// 扫描时统计三种信号
keywordPresent = 树里存在命中关键词的节点(不看可见性)
keywordVisible = 其中 isVisibleToUser = true 的节点
episodeHeader  = 屏幕左上角的「第X集」(X=1..999)

// 判定
keywordVisible           -> NORMAL    // 正常剧集页,不动手
keywordPresent           -> AD        // 关键词还在树里但不可见 = 被广告盖住,上滑
严格模式 + episodeHeader -> AD        // 整棵树被广告替换,靠集数标题认播放页
以上都没有               -> UNKNOWN   // 首页/搜索页,不动手
```

剧集页常驻「选集」。广告盖上来时,那个节点**还在 UI 树里,但 `isVisibleToUser` 已经变成 `false`**。

实测(广告正在播,同时刻):

| 数据源 | 树里「选集」节点 | 其中可见 | 判定 |
|---|---|---|---|
| 无障碍服务 | 2 | **0** | **触发上滑** ✓ |

严格模式解决的是旧规则「只要找不到可见的选集就滑」在首页/搜索页的误触:
那些页面树里根本没有「选集」节点,和「播放页被盖住」是两种可区分的状态。
如果连「选集」节点都被广告整棵树替换掉,就靠顶部左上角的「第X集」标题兜底(只认高度前 15%、
宽度前 55% 的区域,避免把列表卡片里的集数当成标题)。

**误滑换集保护**:每次自动上滑前记录当前集数,手势后校验。如果顶部集数从「第2集」变成「第3集」,
且这几秒内没有用户手动翻页,就判定为误滑 —— 不记成功、立即停止本轮重试、锁定 20 秒
(「选集」重新可见时立即解锁)。第一次误滑仍可能发生(手势已经送出),但不会继续叠加到第 4、5 集。

代价是:如果广告连「选集」和「第X集」都盖掉了,严格模式仍不会动手;需要时可关掉严格模式回到旧行为。

**不做**倒计时文案猜测、不做「广告」标识判断、不做直播/图片类广告结构识别、不点「跳过」按钮。

### 高级规则(如果 / 如果真)

主界面打开「高级规则」后,可以用接近易语言的写法自己组合判断。
默认规则与严格判定等价:

```
# 默认规则:等价于严格判定
如果 可见关键词
    不动作
否则如果 存在关键词
    上滑
否则如果 有集数
    上滑
否则
    不动作
结束如果
```

`如果真` 是 `如果` 的别名(同样以 `结束如果` 收尾):

```
如果真 集数 >= 2 且 集数 <= 999
    上滑
结束如果
```

条件信号:

| 写法 | 含义 |
|---|---|
| `可见关键词` | 当前屏幕能看到关键词(默认「选集」) |
| `存在关键词` | UI 树里有该节点,不管是否可见 |
| `有集数` / `集数` | 顶部左侧识别到「第X集」 |
| `集数 >= 2` | 数值比较,支持 `> < >= <= = !=`;没有集数时结果为假 |
| `文字数 >= 40` | 当前 UI 树去重文字总数比较 |
| `严格模式` / `宽松模式` | 读取主界面的严格判定开关 |

组合:`且`(或 `&&`)、`或`(或 `||`)、`非`(或 `不` / `!`)、括号。
动作:`上滑`、`不动作`(也接受 `不动` / `忽略`)。
注释:`#` 或 `//`。

规则保存时会先校验,格式错误会在编辑框下方显示行号与原因;
运行中解析失败会自动回退内置判定,不会让服务停滞。

---

## 工作时序

判定逻辑集中在 `AdSkipAccessibilityService.执行扫描()`,防误触参数是硬编码常量:

| 常量 | 值 | 作用 |
|---|---|---|
| 扫描间隔 | 默认 1s | 可在主界面 300–5000ms 调整,不管有没有广告都按此节奏 |
| `READY_BUFFER_MS` | 4s | 服务连接后的缓冲期,期间不自动滑(防止恢复瞬间划走正在看的) |
| `USER_SCROLL_GUARD_MS` | 3s | 用户手动翻页后的守卫时间 |
| `SWIPE_COOLDOWN_MS` | 3s | 两次自动上滑的最小间隔 |
| `QUIET_MS` | 3s | 上滑后的安静期,防止错误上滑多次 |
| `POST_SWIPE_PAUSE_MS` | 3s | 手势送达后暂停扫描 3 秒,再恢复扫描 |
| `AD_DEBOUNCE_MS` | 1s | 判定需**持续消失 ≥1 秒**才算结束(一闪而过不算新广告) |
| `AUTO_SWIPE_WINDOW_MS` | 1.5s | 自动上滑引发的滚动落在该时间窗内则不算"用户翻页" |
| `BALL_RETRY_MS` | 15s | 悬浮球自愈重试间隔(定时器 + 无障碍事件共同驱动) |
| `BALL_MISSING_LOG_MS` | 30s | 「悬浮球挂不上」的警告节流 |
| `AD_STUCK_LOG_MS` | 30s | 判定持续成立但已滑过时的日志节流 |
| `MAX_SWIPE_ATTEMPTS` | 10 | 观看时长限制的连滑上限(每轮 = 3s 安静期 + 3×间隔 复核,10 轮约 60 秒) |
| `REQUIRED_MISS_SAMPLES` | 3 | 重试前要连续几次确认关键词不可见 |

**上升沿逻辑**:判定成立且持续存在 → 触发一次上滑,**手势送达时**置 `prevAdContext = true`;关键词回来满 1 秒才复位。复位前扫描循环不再重复上滑(重试权只在 `checkSwipeResult()` 一条路径上,避免两条路径叠着滑)。

**「送达」≠「跳掉」**:`dispatchGesture` 返回 `true` 只表示系统接收并执行了手势,**不含 App 是否响应的语义** —— 手势是绝对的,注入这一环不存在失败。所以日志里「已上滑」和「上滑成功 ✓」是两行,后者只由事后校验宣布。

**开关的检查点必须覆盖所有路径**:`可以动手()`(总开关 + 目标应用)由 `执行扫描()`、`onAccessibilityEvent()`、**`checkSwipeResult()`** 三处共同调用。

> 踩过的坑:最早只在扫描入口判了总开关,重试链是**独立的定时任务**、不经过那个入口,
> 于是关掉开关后已经跑起来的链会无视开关继续滑满 10 次(约 60 秒),
> 表现就是「功能都关了还在上滑」。链里还必须把 `swipeAttempts`/`missSamples`/`prevAdContext` 清零,
> 否则开关再打开时残留的 `prevAdContext = true` 会吃掉上升沿,广告还在屏幕上却再也不滑。

**观看时长限制怎么兜**:滑完约 3 秒校验一次,关键词没回来就再滑一次,最多 10 次。重试前要**连续三次**(每次间隔一个扫描周期)都判「关键词不可见」才动手 —— 广告刚自己放完的那一瞬,UI 树还没重建,单次采样仍读作「不可见」,此时若直接重试就会把剧集页也滑掉、多跳一集。

不去猜倒计时文案或按钮置灰 —— 红果的倒计时是否暴露在 UI 树里没验证过。直接把**重试间隔当成"等的时长"**:5 秒、15 秒、30 秒的限制都自动覆盖。上限兜住那种根本不响应滑动的插屏广告(白滑十次,手势对全屏广告无副作用)。

**手势**(`GestureKit.kt`):屏幕中心从 `0.80h` 向上滑到 `0.15h`,时长 150ms,基于无障碍 `dispatchGesture`,**不需要 root**。

**扫描是双触发**:定时器轮询为主(广告可能是静态视频层,不产生无障碍事件),无障碍事件到达时补一次低延迟扫描。

---

## 界面

单页 Compose,四张卡片,可滚动:

1. **状态卡** — 无障碍服务 + 悬浮球两行状态(绿点 = 正常);**只在异常时**才追加一行原因和「去开启」按钮
2. **用法卡** — 规则一行 + 悬浮球用法一行 + 「打开红果免费短剧」按钮
3. **规则设置卡** — 严格判定开关、判定关键词、扫描间隔、保存按钮、累计统计与重置
4. **日志卡** — 默认折叠只显示最新一条,点标题展开全量,可清空/导出

主界面是**文本类设置的唯一入口**(悬浮窗默认 `FLAG_NOT_FOCUSABLE`,不适合弹输入法)。
总开关和「上滑一次」也放在**通知栏**;悬浮球长按可快速开关。目标应用开关仍在悬浮管理面板里。

---

## 使用

1. 安装 APK,打开 App
2. 点「去开启无障碍服务」,在系统设置中打开「广告自动跳过服务」
3. 点「去开启悬浮球权限」,打开「显示在其他应用上层」
4. 允许通知(Android 13+ 首次启动会主动申请,`GuardService` 保活通知与快捷按钮需要)
5. 进红果免费短剧刷剧

主界面「规则设置」可按需修改关键词和扫描间隔;默认严格判定,首页/搜索页不会误滑。

### ⚠️ 悬浮球权限会被系统重置

**HyperOS(小米)每次安装或更新本 App,都会把 `SYSTEM_ALERT_WINDOW` 重置成 `ignore`**,实测 `adb install -r` 之后立刻就是 `ignore`。

此时的表现是:`WindowManager.addView` **成功返回**、窗口也注册进了 `dumpsys window`,但系统不显示。所以「dumpsys 里有窗口」**不等于**「用户能看到球」。

App 里的处理:

- `ToggleBall.show()` 先查 `Settings.canDrawOverlays()`,没权限就**不挂窗** —— 宁可让 `isShowing` 如实为 `false`,也不要挂一个看不见的窗口骗过自愈逻辑
- `自愈悬浮球()` 由**定时器和无障碍事件共同驱动**每 15 秒重试一次。只靠事件是不够的:红果播放时可能长时间零无障碍事件,用户授权后球会一直不出现
- MainActivity 状态卡常驻显示悬浮球权限状态,每秒轮询,配「去开启悬浮球权限」按钮,用 `ACTION_MANAGE_OVERLAY_PERMISSION` + `package:` uri 直达本应用的权限页

用 adb 部署后可以手动补一次:

```powershell
adb -s <设备> shell appops set com.autoskip.ad SYSTEM_ALERT_WINDOW allow
```

### 权限说明

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮球 / 顶部提示条(注意会被 HyperOS 重置) |
| `FOREGROUND_SERVICE` | `GuardService` 保活 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | `GuardService` 类型声明(避开 Android 15 的 6 小时限制) |
| `POST_NOTIFICATIONS` | 保活通知与通知栏快捷按钮可见 |

manifest 中只声明以上 4 项,**无网络权限、无麦克风权限**。

### 手动验证上滑链路

```powershell
adb -s <设备> shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW
```

走的是和自动跳过完全相同的 `swipeUpOnce()` 路径(仅额外清掉冷却/安静期和重试计数),用于验证 `dispatchGesture` 链路是否通。

> 注意:「链路通」只说明手势送达了系统,不代表广告被跳掉。真正的结果看紧随其后的 `已确认:「选集」回来了,上滑成功 ✓`。

通知栏的「上滑一次」按钮走同一条路径,不需要 adb。

---

## 项目结构

```
app/src/main/java/com/autoskip/ad/
├── AdSkipAccessibilityService.kt   无障碍服务:扫描调度 + 全部时序守卫 + 上滑决策
├── AdDetector.kt                   UI 树扫描(可见/存在/第X集)与关键词匹配
├── AdRule.kt                       纯判定逻辑(严格/宽松),可单元测试
├── GestureKit.kt                   dispatchGesture 上滑注入
├── AdHintOverlay.kt                顶部检测提示条(2.5s 自动消失)
├── ToggleBall.kt                   悬浮球 + 管理面板(拖动、短按面板、长按开关、自愈重挂)
├── GuardService.kt                 前台保活服务(specialUse)+ 通知栏快捷操作
├── ActionReceiver.kt               通知栏按钮的广播落点
├── LogExporter.kt                  日志导出为文本并经 FileProvider 分享
├── Settings.kt                     AppSettings:开关/目标包/关键词/间隔/严格模式/统计
├── LogBuf.kt                       120 条内存环形日志,镜像 logcat
└── MainActivity.kt                 Compose 单页界面(状态/用法/规则设置/日志)

app/src/test/java/com/autoskip/ad/
├── AdRuleTest.kt                   严格模式判定回归
└── SettingsParseTest.kt            关键词解析与匹配回归

app/src/main/res/xml/accessibility_service_config.xml
    accessibilityEventTypes = typeWindowStateChanged | typeWindowContentChanged | typeViewScrolled
    accessibilityFlags       = flagDefault | flagReportViewIds | flagRetrieveInteractiveWindows
    canPerformGestures        = true
    canRetrieveWindowContent  = true
    packageNames              = com.phoenix.read        (红果免费短剧)

app/src/main/res/xml/file_paths.xml          日志导出目录(cache/logs)
app/src/main/res/layout/bubble_toggle.xml    悬浮球布局
app/src/main/res/layout/ball_panel.xml       悬浮管理面板布局
app/src/main/res/layout/ball_panel_row.xml   面板里的目标应用行(按 KNOWN_APPS 动态生成)
```

> **`com.dragon.read` 是番茄免费小说,不是红果。** 两家是字节同源的两个产品,
> 连启动类都共用 `com.dragon.read.pages.splash.SplashActivity`,极易看混。
> 红果免费短剧是 `com.phoenix.read`。

无障碍服务配置要点:`flagReportViewIds` 让服务能读到 `viewIdResourceName`(resource-id);`flagRetrieveInteractiveWindows` 让 `getWindows()` 返回全部可交互窗口。

---

## 构建

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.14(项目自带 wrapper) |
| AGP | 8.13.2 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 36(Android 16) |
| minSdk | 26(Android 8.0) |
| versionCode / versionName | 145 / 1.2.0 |

项目自带 Gradle wrapper,无需预装 Gradle:

```powershell
$env:ANDROID_HOME = "C:\Android"
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleRelease --console=plain
```

SDK 路径写进 `local.properties`(该文件不入库):

```properties
sdk.dir=C\:\\Android
```

产物:

- debug:`app/build/outputs/apk/debug/app-debug.apk`
- release:`app/build/outputs/apk/release/app-release.apk`

### 发布签名

默认 **release 变体沿用 debug 签名**,用于自用分发 —— 这样能直接覆盖安装现有的
debug 版,不会因签名不一致被迫卸载。正式密钥准备好后,在仓库根目录创建
`keystore.properties`(参考 `keystore.properties.example`),重新构建即可自动切换,
该文件已被 `.gitignore` 排除。

> ⚠️ 从 debug 签名切到正式签名时,已安装的设备必须先卸载旧版,App 设置会被清空。

---

## 部署

> ⚠️ **不要用 `am force-stop` 重启本 App**。`force-stop` 会把包标记为 stopped,系统会**自动禁用**该包的辅助功能服务(`enabled_accessibility_services` 被清空),表现为"自己关了"。用 `adb shell am kill com.autoskip.ad` 或直接 `install -r` 覆盖安装即可。

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\release\app-release.apk
& $adb shell settings put secure enabled_accessibility_services com.autoskip.ad/.AdSkipAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell appops set com.autoskip.ad SYSTEM_ALERT_WINDOW allow
& $adb shell pm grant com.autoskip.ad android.permission.POST_NOTIFICATIONS
& $adb shell am start -n com.autoskip.ad/.MainActivity
```

> 无障碍开关必须写在 `install` **之后**,否则重装会被重置。
> 重装后建议顺手核对一次:`adb shell settings get secure enabled_accessibility_services`

无线调试配对与无障碍开关校验脚本不随仓库提供,按需用 adb 手工执行。

抓日志:

```powershell
& $adb logcat -s AutoSkip:V
```

---

## 已知限制与待办

### 检测侧

**严格判定 = 关键词存在但不可见。** 只数 `isVisibleToUser = true` 的节点判断"可见",同时统计不看可见性的节点判断"播放页特征"。广告盖上来时「选集」节点仍在树里(实测 2 个)但可见数为 0,规则才成立。

**严格模式的代价**:如果广告把「选集」节点和顶部「第X集」标题都盖掉,就不会触发。此时可在主界面关闭「严格判定」回到旧规则 —— 旧规则的代价是首页/搜索页也会被滑。

**满屏品牌广告跳不了,不是判定问题。**

实测这类广告:active 状态下手动触发 `dispatchGesture` 上滑,画面不变;用 shell 的 `input swipe` 做真实触摸注入,画面同样不变。整棵树 59 节点、1 个文字、唯一可点节点是「立即领取」`[1017,1021][1185,1207]` —— **设计上就是必须看完的插屏广告,没给任何跳过出口。**

注意这不是手势没送到:`dispatchGesture` 返回 `true`、shell 的 `input swipe` 也是真实触摸,两者都执行了,广告只是不响应滑动。`MAX_SWIPE_ATTEMPTS` 重试 10 次后放弃。

**早期多信号判据的探测结论**(那套代码已删,留档说明为什么现在只用关键词):

- 满屏节点判据 —— `#dsc` / `#l0` 这类 ViewPager 容器在广告页和剧集页 id 相同,区分不了
- `ttlive_player_render_view` —— 离线样本里区分度 perfect(4/4 命中广告、0/25 命中正常),但服务树里读不到这个节点
- `getWindows()` 全量扫描 —— 恒为 3 个窗口(`com.miui.home` / `com.android.systemui` / `com.phoenix.read`),广告窗口始终不在其中
- `refresh()` 强制刷新 —— 文字数 34→34,内容不变,无节点缓存可刷
- 纯事件驱动(`typeWindowsChanged`)—— 前提同样是能看到广告窗口,已证伪

### 已知风险

严格模式已经解决首页/搜索页/榜单页的误滑,但**任何盖住播放页的界面仍会被当成广告滑掉**,包括:

- 通知栏 / 控制中心 —— 实测拉下通知栏时,剧集页的「选集」节点 `isVisibleToUser` 立刻变 `false`,于是触发上滑(把通知栏推回去了,行为可接受)
- 分享面板、领取弹窗等模态层
- 系统权限弹窗

这是「关键词 + 可见性」这条规则的固有代价。

### 其他工程问题

- `specialUse` 前台服务适用于自用/侧载分发;若要上架 Google Play,需要改用符合政策的类型(如 `dataSync` + 处理 6 小时限制)
- 锁屏状态下无法注入手势
- `LogBuf` 是纯内存,进程被杀即丢 —— 可通过「导出运行日志」在排查时保存
- 尚无 CI;本地最低验证命令为 `gradlew testDebugUnitTest assembleRelease`
- `targetSdk 36` 下 `TYPE_APPLICATION_OVERLAY` 与无障碍行为仍需真机回归(本机无设备,未做真机验证)

---

## 隐私

无网络代码,不读写任何账号或用户数据。所有判定都在设备本地完成。
`INTERNET` 权限未声明。「导出运行日志」只是把本地文本交给系统分享面板,由用户自己选择落点。
