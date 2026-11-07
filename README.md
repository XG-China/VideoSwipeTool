# 通用视频上滑工具 (VideoSwipeTool)

开车、骑车刷视频时,不用再手动划走广告。

用三组关键词判断当前页面:

- 剧集页(选集 / 第N集)和首页(关注 / 推荐 / 首页)不滑
- 广告页(返回 / 上滑继续看短剧 / 点击进入直播间 / 直播中)上滑
- 什么都没命中,也按广告页上滑

默认不开启任何 App;在主界面或悬浮面板里勾选需要自动上滑的视频应用。
广告有倒计时会等结束;上滑后如果多跳了一集,会自动滑回来。

## 下载

- [正式版](https://github.com/XG-China/VideoSwipeTool/releases/latest):新安装 / 分享给别人
- [自用升级版](https://github.com/XG-China/VideoSwipeTool/releases/latest):覆盖旧版安装,保留设置和统计

## 怎么用

1. 安装 APK,打开 App
2. 开启「无障碍服务」
3. 允许「显示在其他应用上层」和通知
4. 在主界面的「目标应用」里勾选要自动上滑的 App
5. 打开该 App 刷视频

> 只对勾选的目标应用生效。**manifest 中没有 INTERNET 权限**,不联网、不上传任何数据。
> 全屏识字(OCR)用的是随 APK 打包的离线模型,截图只在内存中识别后立即回收,不落盘。

## 功能

- 自动上滑跳过广告
- 三组关键词判断页面,剧集页/首页不误滑
- 无障碍树读不到文字时,用本地 OCR 全屏识字补判广告页(Android 11+)
- 识别 `5s` / `5秒` / `广告 5` 等倒计时,结束再滑
- 误滑多跳一集时自动回退
- 悬浮球 + 通知栏快捷开关
- 运行日志、统计、日志导出

---

## 支持应用

默认一个都不开启。内置常见视频/短剧 App(红果、番茄、抖音、快手、西瓜、B站、腾讯视频、爱奇艺、优酷、芒果TV、小红书、微博等),在主界面「目标应用」或悬浮面板里勾选。

不同 App 的页面关键词可能不同,可以在「规则设置」里调整三组关键词。

## 页面判定

三组关键词都可以在主界面修改:

| 页面 | 默认关键词 | 动作 |
|---|---|---|
| 剧集页 | `选集`、`第N集` | 不滑 |
| 首页 | `关注`、`推荐`、`首页` | 不滑 |
| 广告页 | `返回`、`上滑继续看短剧`、`上滑继续观看短剧`、`点击进入直播间`、`直播中`、`直播间` | 上滑 |

优先级:

1. 命中「返回」以外的**广告强关键词** → 广告页,上滑
2. 命中**剧集页关键词** → 剧集页,不滑
3. 命中**首页关键词** → 首页,不滑
4. 只命中 `返回`,或什么都没命中 → 广告页,上滑

几个要点:

- 「返回」是**弱信号**:单独出现时不会覆盖剧集/首页,避免把剧集页左上角的返回按钮、金币弹窗误判成广告
- 屏幕上还能看到「选集」时,**一律不自动上滑**;只有广告真正盖住内容、读不到「选集」时才按广告处理
- 弹窗盖住集数标题、但「选集」还露在外面时,不会再因为弹窗里的「返回」而误上滑
- 命中多个关键词时,按关键词列表顺序取第一个显示,三组都一样
- 「第N集」的 N 是通配符,不是字面量

实测(广告正在播,同时刻):

| 数据源 | 树里「选集」节点 | 其中可见 | 判定 |
|---|---|---|---|
| 无障碍服务 | 2 | **0** | **触发上滑** ✓ |

**误滑换集保护**:每次自动上滑前记录当前集数,手势后校验。如果顶部集数从「第2集」变成「第3集」,
且这几秒内没有用户手动翻页,就判定为误滑 —— 不记成功、立即停止本轮重试、锁定 20 秒
(回到剧集页时立即解锁)。第一次误滑仍可能发生(手势已经送出),但不会继续叠加到第 4、5 集。

**不做**倒计时文案猜测、不做「广告」标识判断、不点「跳过」按钮。直播/图片类广告优先交给下面的全屏识字补充。

### 全屏识字(OCR,Android 11+)

有些直播广告是 Surface/Canvas 渲染的,无障碍树里完全没有文字,只能靠看屏幕:

1. 页面被判定为广告页、但树里既没有强广告词也没有「返回」时,服务截一张屏
2. 用 ML Kit 中文离线模型识别整屏文字(截图缩放后识别,识别完立即回收)
3. 识别到广告词(如「点击进入直播间」「直播中」「上滑继续看短剧」)→ 强制按广告页处理并上滑
4. 识别到「选集」→ 判定仍是剧集页,**这一轮不上滑**(用户规则:选集还看得见就不滑)
5. 识别超时或失败 → 按原来的「空关键词按广告页」逻辑继续,不会卡住

**树停在旧页面怎么办**:红果的直播广告是盖在剧集页上的 Surface,不会产生窗口事件,
无障碍树会一直停在剧集页(「选集」仍可见)。所以剧集页每 **3 秒**做一次 OCR 巡检,
看到 `ttlive_player_render_view` 等广告遮罩时 **1 秒内**立即触发;
命中广告词就上滑,正常剧集页只读到「选集」不会动作。

剧集页不再把 OCR 读到的「返回」当成广告(选集面板、金币弹窗也有「返回」);
「返回」只在无障碍树已经判定为广告页时才作为弱信号使用。首页不做 OCR 覆盖。

**广告遮罩识别**:扫描 UI 树时同时采集可见节点的 `class / view-id`,命中字节广告 SDK
或红果自己的遮罩(`seriestopview`、`openingscreenad`、`adwebview`、`adplayable`、
`openadsdk`、`pangle`、`tt_ad`、`tt_splash` 等)就直接按广告页处理。
页面日志会打印 `遮罩=` 和 `视图=`。

**命中率**:OCR 文本先去掉空白和标点,长关键词允许 1–2 个识别错字
(例如「上滑继续观看短刷」也能命中「上滑继续观看短剧」);多行文字用无空格方式拼接,
避免关键词被拆行后匹配不到。

**性能与速度**:剧集页 3 秒一次 OCR(尽量 3 秒内反应),首页 8 秒;
看到 `ttlive_player_render_view` / `seriestopview` 等广告遮罩 view 时 1 秒内立即触发。
识别宽度 480/360,截图和缩放放在独立线程。每次 OCR 约 0.3–1.1 秒;
剧集页 OCR 巡检会增加耗电,不需要 OCR 时可在「规则设置」关闭。

部分广告有「N秒后可继续上滑」的观看门槛,App 会等待这一次门槛;
剧集页/广告页来回跳时不会重复计时,门槛结束后立即上滑。

**持久日志**:运行日志同时写入 `filesDir/logs/autoskip.log`,
超过 2MB 轮转为 `autoskip.log.1`,只保留两份;内存保留 1000 行。
「导出运行日志」会优先附带持久化文件的最近 2000 行,进程被杀前的记录也能看到。

**无障碍窗口完全为空怎么办**:有些 App/模拟器会屏蔽无障碍窗口(焦点正常,但 root 一直是 null)。
这时只要前台是目标 App,就每 5 秒做一次纯 OCR 兜底;命中广告词直接上滑,
不再依赖无障碍树。红果在 Android 11 ARM 翻译模拟器上就是这种状态,已验证 OCR 能读到画面。

识别结果还会校验页面是否已经变化,过期结果直接丢弃。
可在「规则设置」里关闭。OCR 只在本地内存处理,不保存、不上传。

> 注意:首页/搜索页如果没命中首页关键词,也会按广告页触发一次上滑。
> 上升沿逻辑保证同一状态只滑一次,不会在首页连续滑。

**广告倒计时**:如果广告下方出现 `5s` / `5秒` / `广告 5` / `15秒后可跳过` 这类倒计时,
App 会按显示的秒数等待,倒计时结束后再上滑;同一倒计时文本 8 秒不变则视为误判,不再等待,
最长等待 61 秒。倒计时结束后提示条会显示「已上滑到第X集」。

### 误滑换集自动回退(补充功能)

这是给"广告触发了几次自动上滑之后"用的**事后校验**,不是主判定规则:

1. 自动上滑前记录当前集数 N(例如第 11 集)
2. 上滑后:
   - 仍是第 N 集 → 广告被滑掉,正常
   - 第 N+1 集 → 正好是下一集,正常结束
   - 第 N+2 集或更远 → 目标设为第 N+1 集,开始下滑回退
3. 每次下滑后等待 2.5 秒,重新读取顶部「第X集」:
   - 到达目标 → 结束,并静默 10 秒
   - 仍大于目标 → 再下滑,最多 3 次
   - 小于目标(回退过头)→ 停止,避免来回震荡
4. 回退过程中用户手动翻页、关闭总开关或手动触发上滑 → 立即中止回退

例:第 11 集播完后广告触发上滑,结果到了第 13 集,App 会下滑一次回到第 12 集。
用户自己滑动、通知栏「上滑一次」、adb 手动触发都不会进入这套回退逻辑。

主界面「误滑自动回退」开关默认开启,可单独关闭。

---

## 工作时序

判定逻辑集中在 `AdSkipAccessibilityService.执行扫描()`。防误触参数都可以在主界面「高级时序」里调整,下表是默认值:

| 参数 | 默认值 | 作用 |
|---|---|---|
| 扫描间隔 | 默认 1s | 可在主界面 1000–5000ms 调整,不管有没有广告都按此节奏 |
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

单页 Compose,六张卡片,可滚动:

1. **状态卡** — 无障碍服务 + 悬浮球 + 目标应用三行状态;异常时才追加原因和「去开启」按钮
2. **用法卡** — 规则一行 + 悬浮球用法一行 + 「打开目标应用」按钮
3. **规则设置卡** — 剧集/首页/广告三组关键词、扫描间隔、误滑自动回退开关、全屏识字开关、保存按钮、自动统计与重置
4. **目标应用卡** — 勾选需要自动上滑的视频 App,默认一个都不开
5. **高级时序卡** — 连接缓冲、守卫、冷却、安静期、重试次数等参数,默认折叠
6. **日志卡** — 默认折叠只显示最新一条,点标题展开全量,可清空/导出

主界面是**文本类设置的唯一入口**(悬浮窗默认 `FLAG_NOT_FOCUSABLE`,不适合弹输入法)。
总开关和「上滑一次」也放在**通知栏**;悬浮球长按可快速开关。目标应用开关在主界面和悬浮面板里都能改。

## 使用

1. 安装 APK,打开 App
2. 点「去开启无障碍服务」,在系统设置中打开「视频自动上滑服务」
3. 点「去开启悬浮球权限」,打开「显示在其他应用上层」
4. 允许通知(Android 13+ 首次启动会主动申请,`GuardService` 保活通知与快捷按钮需要)
5. 在「目标应用」里勾选要自动上滑的 App
6. 打开该 App 刷视频

主界面「规则设置」可按需修改三组关键词和扫描间隔。

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
手动触发只做一次结果确认,**不计入统计、不进入自动重试/回退链**。

---

## 项目结构

```
app/src/main/java/com/autoskip/ad/
├── AdSkipAccessibilityService.kt   无障碍服务:扫描调度 + 全部时序守卫 + 上滑决策
├── AdDetector.kt                   UI 树扫描(关键词/集数/倒计时)与匹配
├── AdRule.kt                       纯判定逻辑(所有关键词可见才不滑),可单元测试
├── EpisodeCorrection.kt            误滑换集的纯逻辑(下一集/回退目标),可单元测试
├── GestureKit.kt                   dispatchGesture 上滑注入
├── OcrHelper.kt                    ML Kit 中文离线识字(截图只在内存处理)
├── AdHintOverlay.kt                顶部检测提示条(2.5s 自动消失)
├── ToggleBall.kt                   悬浮球 + 管理面板(拖动、短按面板、长按开关、自愈重挂)
├── GuardService.kt                 前台保活服务(specialUse)+ 通知栏快捷操作
├── ActionReceiver.kt               通知栏按钮的广播落点
├── LogExporter.kt                  日志导出为文本并经 FileProvider 分享
├── Settings.kt                     AppSettings:开关/目标包/关键词/间隔/回退/统计
├── LogBuf.kt                       1000 条内存日志 + 2MB 轮转持久化文件,镜像 logcat
└── MainActivity.kt                 Compose 单页界面(状态/用法/规则设置/日志)

tools/monitor-phone.ps1             真机 24 小时监控:抓 logcat、定时截屏、检测卡广告/误滑/异常

app/src/test/java/com/autoskip/ad/
├── AdRuleTest.kt                   页面分类优先级回归
├── EpisodeCorrectionTest.kt        换集回退目标回归
└── SettingsParseTest.kt            关键词/集数/倒计时/命中顺序回归

app/src/main/res/xml/accessibility_service_config.xml
    accessibilityEventTypes = typeWindowStateChanged | typeViewScrolled
    accessibilityFlags       = flagDefault | flagReportViewIds | flagRetrieveInteractiveWindows | flagIncludeNotImportantViews
    canPerformGestures        = true
    canRetrieveWindowContent  = true
    canTakeScreenshot         = true
    notificationTimeout       = 800ms
    (不限定 packageNames,服务接收所有 App 事件,在代码里按目标应用集合过滤)
    (不订阅高频 typeWindowContentChanged;判定交给 1 秒定时扫描,省电)

app/src/main/res/xml/file_paths.xml          日志导出目录(cache/logs)
app/src/main/res/layout/bubble_toggle.xml    悬浮球布局
app/src/main/res/layout/ball_panel.xml       悬浮管理面板布局(目标应用列表可滚动)
app/src/main/res/layout/ball_panel_row.xml   面板里的目标应用行(按 KNOWN_APPS 动态生成)
```

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
| versionCode / versionName | 163 / 1.6.9 |

> 全屏识字依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`(中文离线模型),
> 1.6.9 起按 CPU 架构拆包:arm64 单包约 14MB,v7a 约 10MB
> (之前四架构合在一起约 45MB,主要差在 ML Kit 的 OCR 原生库)。

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

产物(按 ABI 拆分):

- debug:`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`、`app-armeabi-v7a-debug.apk`
- release:`app/build/outputs/apk/release/app-arm64-v8a-release.apk`、`app-armeabi-v7a-release.apk`
- 现在的手机绝大多数用 **arm64-v8a**;只有老 32 位设备才需要 v7a

### 发布签名

正式版使用仓库根目录的 `release.jks` + `keystore.properties`（两者都被 `.gitignore` 排除）。
`keystore.properties` 里记录 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；
**务必单独备份这两个文件**，丢失后无法用同一签名更新已安装的正式版。

当前提供三个产物：

- `dist/VideoSwipeTool-1.6.9-arm64-release.apk`：正式签名，arm64 手机（绝大多数），约 14MB
- `dist/VideoSwipeTool-1.6.9-v7a-release.apk`：正式签名，老 32 位手机，约 10MB
- `dist/VideoSwipeTool-1.6.9-arm64-debug-signed.apk`：debug 签名，可直接覆盖升级现有的自用版本，
  保留统计和设置；证书显示 `Android Debug`，不适合对外分发。

正式签名版和 debug 签名版不能互相覆盖；已安装 debug 签名版的设备必须先卸载才能安装正式版。

临时构建 debug 签名版：把 `keystore.properties` 移走后再执行 `assembleRelease` 即可
（`app/build.gradle.kts` 会回退到 debug 签名）。

---

## 部署

> ⚠️ **不要用 `am force-stop` 重启本 App**。`force-stop` 会把包标记为 stopped,系统会**自动禁用**该包的辅助功能服务(`enabled_accessibility_services` 被清空),表现为"自己关了"。用 `adb shell am kill com.autoskip.ad` 或直接 `install -r` 覆盖安装即可。

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\release\app-arm64-v8a-release.apk
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

日志里每 20 秒会有一条 `[心跳]`:

- `窗口=com.phoenix.read`:已经拿到目标应用的根节点
- `窗口=?`:没有拿到根节点(1.6.4 已增加从 `windows` 列表兜底)
- `页面=广告页/剧集页/首页`,以及 `OCR忙/锁定/冷却/暂停` 状态

排查"为什么不动"时,先看最近一条心跳和 `[页面]` 日志。

---

## 已知限制与待办

### 检测侧

**判定 = 三组关键词的页面分类。** 只数 `isVisibleToUser = true` 的节点判断"可见";
广告强关键词优先,其次是剧集页、首页,最后才是「返回」/无关键词。

**代价**:首页/搜索页如果没命中首页关键词,会按广告页触发一次上滑;上升沿逻辑保证同一状态只滑一次。
关键词可自定义,规则不写死具体词。

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

新规则下,首页/搜索页等凑不齐两个关键词的页面也会触发一次上滑;**任何盖住播放页的界面同样会被滑掉**,包括:

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
manifest 中没有任何网络权限(`INTERNET` / `ACCESS_NETWORK_STATE` 都没有),也不申请安装未知应用权限。
「导出运行日志」只是把本地文本交给系统分享面板,由用户自己选择落点。

全屏识字使用随 APK 打包的 ML Kit 中文离线模型,不需要联网下载。
截图由无障碍服务的 `takeScreenshot` 获取,只在内存中识别,识别完立即回收,
不写入文件、不上传;关闭「全屏识字」后完全不会截屏。
