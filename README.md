# 红果免费短剧广告自动跳过 (AutoSkip Ad)

原生 Android App(Kotlin + Jetpack Compose),通过无障碍服务在**红果免费短剧**内「屏幕上找不到『选集』二字时」自动上滑跳过。
**零第三方依赖,纯本地 UI 树解析,不联网、不 OCR、不自动点按钮。**

---

## 功能

- **自动上滑跳过**:每秒扫描一次当前无障碍窗口,屏幕上找不到「选集」二字就自动上滑一次
- **只作用于红果免费短剧**:`com.phoenix.read`,config 层的 `packageNames` 已把事件源限死在这个包,其他 App 完全收不到事件
- **不抢用户操作**:用户手动翻页后 3 秒内不动作
- **只处理"新出现"的判定**:同一状态绝不重复滑
- **广告有时限也能跳**:上滑后校验一次,没跳掉就继续滑,重试前要连续三次复核,最多等 60 秒
- **悬浮球 + 管理面板**:屏幕边缘的红色小球,**点击打开管理面板**,总开关和目标应用都在面板里改;球可拖动换位置(自动记忆)
- **顶部提示条**:上滑时短暂显示「已上滑 ✓」,约 2.5 秒后自动消失;是否真的跳掉由随后的校验日志确认
- **运行日志**:App 内实时日志区,保留最近 120 条,同时镜像到 logcat(`AutoSkip` tag)

---

## 判定规则(只此一条)

`app/src/main/java/com/autoskip/ad/AdDetector.kt`:

```kotlin
private val ZH_EPISODE = listOf("选集")
```

**屏幕上找不到「选集」二字 → 上滑。**

```kotlin
if (!有选集 && node.isVisibleToUser) { ... }   // 只认可见节点
val adVisible = scan.无选集
```

剧集页常驻「选集」。广告盖上来时,那个节点**还在 UI 树里,但 `isVisibleToUser` 已经变成 `false`** —— 所以必须只数可见的,否则规则永远不成立。

实测(广告正在播,同时刻):

| 数据源 | 树里「选集」节点 | 其中可见 | 判定 |
|---|---|---|---|
| 无障碍服务 | 2 | **0** | **触发上滑** ✓ |

**不做**「上滑继续观看短剧」提示判断、不做「广告」标识判断、不做关键词表匹配、不做直播/图片类广告结构识别、不做超时兜底、不点「跳过」按钮。

---

## 工作时序

判定逻辑集中在 `AdSkipAccessibilityService.执行扫描()`,防误触参数是硬编码常量:

| 常量 | 值 | 作用 |
|---|---|---|
| `SCAN_THROTTLE_MS` | 1s | 扫描节奏,固定每秒一次,不管有没有广告 |
| `READY_BUFFER_MS` | 4s | 服务连接后的缓冲期,期间不自动滑(防止恢复瞬间划走正在看的) |
| `USER_SCROLL_GUARD_MS` | 3s | 用户手动翻页后的守卫时间 |
| `SWIPE_COOLDOWN_MS` | 3s | 两次自动上滑的最小间隔 |
| `QUIET_MS` | 3s | 上滑后的安静期,防止错误上滑多次 |
| `POST_SWIPE_PAUSE_MS` | 3s | 手势送达后暂停扫描 3 秒,再恢复每秒扫描 |
| `AD_DEBOUNCE_MS` | 1s | 判定需**持续消失 ≥1 秒**才算结束(一闪而过不算新广告) |
| `AUTO_SWIPE_WINDOW_MS` | 1.5s | 自动上滑引发的滚动落在该时间窗内则不算"用户翻页" |
| `BALL_RETRY_MS` | 15s | 悬浮球自愈重试间隔(定时器 + 无障碍事件共同驱动) |
| `BALL_MISSING_LOG_MS` | 30s | 「悬浮球挂不上」的警告节流 |
| `AD_STUCK_LOG_MS` | 30s | 判定持续成立但已滑过时的日志节流 |
| `MAX_SWIPE_ATTEMPTS` | 10 | 观看时长限制的连滑上限(每轮 = 3s 安静期 + 3×1s 复核 = 6s,10 轮 ≈ 60 秒) |
| `REQUIRED_MISS_SAMPLES` | 3 | 重试前要连续几次确认「找不到选集」 |

**上升沿逻辑**:判定成立且持续存在 → 触发一次上滑,**手势送达时**置 `prevAdContext = true`;「选集」回来满 1 秒才复位。复位前扫描循环不再重复上滑(重试权只在 `checkSwipeResult()` 一条路径上,避免两条路径叠着滑)。

**「送达」≠「跳掉」**:`dispatchGesture` 返回 `true` 只表示系统接收并执行了手势,**不含 App 是否响应的语义** —— 手势是绝对的,注入这一环不存在失败。所以日志里「已上滑」和「上滑成功 ✓」是两行,后者只由事后校验宣布。

**开关的检查点必须覆盖所有路径**:`可以动手()`(总开关 + 目标应用)由 `执行扫描()`、`onAccessibilityEvent()`、**`checkSwipeResult()`** 三处共同调用。

> 踩过的坑:最早只在扫描入口判了总开关,重试链是**独立的定时任务**、不经过那个入口,
> 于是关掉开关后已经跑起来的链会无视开关继续滑满 10 次(约 60 秒),
> 表现就是「功能都关了还在上滑」。链里还必须把 `swipeAttempts`/`missSamples`/`prevAdContext` 清零,
> 否则开关再打开时残留的 `prevAdContext = true` 会吃掉上升沿,广告还在屏幕上却再也不滑。

**观看时长限制怎么兜**:滑完约 3 秒校验一次,「选集」没回来就再滑一次,最多 10 次。重试前要**连续三次**(每次间隔 1 秒,共跨 2 秒)都判「找不到选集」才动手 —— 广告刚自己放完的那一瞬,UI 树还没重建,单次采样仍读作「找不到选集」,此时若直接重试就会把剧集页也滑掉、多跳一集。

不去猜倒计时文案或按钮置灰 —— 红果的倒计时是否暴露在 UI 树里没验证过。直接把**重试间隔当成"等的时长"**:5 秒、15 秒、30 秒的限制都自动覆盖。上限兜住那种根本不响应滑动的插屏广告(白滑十次,手势对全屏广告无副作用)。

**手势**(`GestureKit.kt`):屏幕中心从 `0.80h` 向上滑到 `0.15h`,时长 150ms,基于无障碍 `dispatchGesture`,**不需要 root**。

**扫描是双触发**:定时器每秒轮询为主(广告可能是静态视频层,不产生无障碍事件),无障碍事件到达时补一次低延迟扫描。

---

## 界面

单页 Compose,三张卡片,一屏放得下:

1. **状态卡** — 无障碍服务 + 悬浮球两行状态(绿点 = 正常);**只在异常时**才追加一行原因和「去开启」按钮,正常时零解释性文字
2. **用法卡** — 规则一行 + 悬浮球用法一行 + 「打开红果免费短剧」按钮
3. **日志卡** — 默认折叠只显示最新一条,点标题展开全量,可清空

刻意不做的事:不显示包名(对使用者无意义)、不显示原理讲解、不重复「只对红果生效」这类已在无障碍服务描述里的话。

总开关和目标应用开关**只在悬浮管理面板里**,主界面不重复。
面板本身:点球弹出,球靠右就往左展开、靠左往右展开,一律夹在屏内;点面板外或点关闭收起。面板里只列应用名,不列包名。
日志通过 `MainActivity.ACTION_ACC_STATE` 广播与服务连接状态同步;该广播不带 extra,主界面收到后自己重查 `enabled_accessibility_services` —— 那是唯一事实来源。

---

## 使用

1. 安装 APK,打开 App
2. 点「去开启无障碍服务」,在系统设置中打开「广告自动跳过服务」
3. 点「去开启悬浮球权限」,打开「显示在其他应用上层」
4. 允许通知(`GuardService` 保活前台服务需要)
5. 进红果免费短剧刷剧

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
| `FOREGROUND_SERVICE_DATA_SYNC` | 同上 |
| `POST_NOTIFICATIONS` | 保活通知可见 |

manifest 中只声明以上 4 项,**无网络权限、无麦克风权限**。

### 手动验证上滑链路

```powershell
adb -s <设备> shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW
```

走的是和自动跳过完全相同的 `swipeUpOnce()` 路径(仅额外清掉冷却/安静期和重试计数),用于验证 `dispatchGesture` 链路是否通。

> 注意:「链路通」只说明手势送达了系统,不代表广告被跳掉。真正的结果看紧随其后的 `已确认:「选集」回来了,上滑成功 ✓`。

---

## 项目结构

```
app/src/main/java/com/autoskip/ad/
├── AdSkipAccessibilityService.kt   无障碍服务:扫描调度 + 全部时序守卫 + 上滑决策
├── AdDetector.kt                   UI 树扫描与关键词匹配(唯一判定逻辑所在)
├── GestureKit.kt                   dispatchGesture 上滑注入
├── AdHintOverlay.kt                顶部检测提示条(2.5s 自动消失)
├── ToggleBall.kt                   悬浮球 + 点击弹出的管理面板(可拖动、位置记忆、自愈重挂)
├── GuardService.kt                 前台保活服务,防 MIUI 冻结导致无障碍断连
├── Settings.kt                     AppSettings:总开关 + 目标包名 + 版本迁移
├── LogBuf.kt                       120 条内存环形日志,镜像 logcat
└── MainActivity.kt                 Compose 单页界面

app/src/main/res/xml/accessibility_service_config.xml
    accessibilityEventTypes = typeWindowStateChanged | typeWindowContentChanged | typeViewScrolled
    accessibilityFlags       = flagDefault | flagReportViewIds | flagRetrieveInteractiveWindows
    canPerformGestures        = true
    canRetrieveWindowContent  = true
    packageNames              = com.phoenix.read        (红果免费短剧)

app/src/main/res/layout/bubble_toggle.xml   悬浮球布局
app/src/main/res/layout/ball_panel.xml      悬浮管理面板布局
app/src/main/res/layout/ball_panel_row.xml  面板里的目标应用行(按 KNOWN_APPS 动态生成)
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
| Gradle | 8.9+ (AGP 8.7.3 的最低要求;本机实测 8.14) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26(Android 8.0) |
| versionCode / versionName | 142 / 1.0.42 |

项目自带 Gradle wrapper,无需预装 Gradle:

```powershell
$env:ANDROID_HOME = "C:\Android"
.\gradlew.bat assembleDebug --console=plain
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

**判定极简(只此一条):屏幕上找不到「选集」二字 → 上滑。**

关键点:只数 `isVisibleToUser = true` 的节点。广告盖上来时「选集」节点仍在树里(实测 2 个)但可见数为 0,加上可见性判断规则才成立。

**满屏品牌广告跳不了,不是判定问题。**

实测这类广告:active 状态下手动触发 `dispatchGesture` 上滑,画面不变;用 shell 的 `input swipe` 做真实触摸注入,画面同样不变。整棵树 59 节点、1 个文字、唯一可点节点是「立即领取」`[1017,1021][1185,1207]` —— **设计上就是必须看完的插屏广告,没给任何跳过出口。**

注意这不是手势没送到:`dispatchGesture` 返回 `true`、shell 的 `input swipe` 也是真实触摸,两者都执行了,广告只是不响应滑动。`MAX_SWIPE_ATTEMPTS` 重试 10 次后放弃。

**早期多信号判据的探测结论**(那套代码已删,留档说明为什么现在只用「选集」):

- 满屏节点判据 —— `#dsc` / `#l0` 这类 ViewPager 容器在广告页和剧集页 id 相同,区分不了
- `ttlive_player_render_view` —— 离线样本里区分度 perfect(4/4 命中广告、0/25 命中正常),但服务树里读不到这个节点
- `getWindows()` 全量扫描 —— 恒为 3 个窗口(`com.miui.home` / `com.android.systemui` / `com.phoenix.read`),广告窗口始终不在其中
- `refresh()` 强制刷新 —— 文字数 34→34,内容不变,无节点缓存可刷
- 纯事件驱动(`typeWindowsChanged`)—— 前提同样是能看到广告窗口,已证伪

最后靠 `isVisibleToUser` 才把「选集」这条单判据跑通:广告盖上来时那个节点仍在树里(实测 2 个)但可见数为 0。

### 已知风险

规则是「屏幕上找不到『选集』就上滑」,所以**任何盖住剧集页的界面都会被当成广告滑掉**,不只广告:

- 首页、搜索页、榜单页 —— 本来就没有「选集」
- 通知栏 / 控制中心 / 弹窗 —— 实测拉下通知栏时,剧集页的「选集」节点 `isVisibleToUser` 立刻变 `false`,于是触发上滑(把通知栏推回去了,行为可接受)
- 分享面板、领取弹窗等模态层

这是「只看一个词」这条规则的固有代价,按需求保留。若要收紧,加前置条件「必须正在 `ShortSeriesActivity` 播放页」即可,一行代码。

### 其他工程问题

- `GuardService` 用 `dataSync` 前台服务类型,Android 15+ 对该类型有每日 6 小时时长上限
- `ToggleBall` 位置用 `FLAG_LAYOUT_NO_LIMITS` + 屏幕绝对坐标存储,分辨率变化或拖出屏幕后可能存到不可见位置(实测出现过 `(-15,-40)`)
- 锁屏状态下无法注入手势
- `LogBuf` 是纯内存,进程被杀即丢
- 无障碍服务销毁时没有注销 `ACTION_SWIPE_NOW` 广播接收器,重连后可能重复注册

---

## 隐私

无网络代码,不读写任何账号或用户数据。所有判定都在设备本地完成。
`INTERNET` 权限已从 manifest 移除。
