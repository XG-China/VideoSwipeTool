param(
    [string]$Serial = "10.0.0.94:39619",
    [int]$DurationMinutes = 1440,
    [int]$IntervalSeconds = 15,
    [string]$Adb = "C:\Android\platform-tools\adb.exe"
)

$ErrorActionPreference = "SilentlyContinue"
$root = Join-Path $PSScriptRoot "..\logs"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $root "monitor-$stamp"
$shots = Join-Path $out "shots"
New-Item -ItemType Directory -Force $out | Out-Null
New-Item -ItemType Directory -Force $shots | Out-Null
$statusFile = Join-Path $out "status.log"
$alertFile = Join-Path $out "alerts.log"
$logFile = Join-Path $out "autoskip.log"
$crashFile = Join-Path $out "crash.log"

function Write-Status([string]$msg) {
    Add-Content -Path $statusFile -Value ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
}
$lastAlertAt = @{}
function Write-Alert([string]$msg, [int]$CooldownSeconds = 90, [string]$Key = "") {
    # 同一类告警在冷却期内只记一次,避免 OCR/页面反复命中时刷屏。
    # 动态消息(如 ad page stuck 998s)必须传固定 Key,否则每次秒数不同会绕过冷却。
    $alertKey = if ($Key) { $Key } else { $msg }
    $nowAlert = Get-Date
    if ($lastAlertAt.ContainsKey($alertKey) -and
        ($nowAlert - $lastAlertAt[$alertKey]).TotalSeconds -lt $CooldownSeconds) {
        return
    }
    $lastAlertAt[$alertKey] = $nowAlert
    Add-Content -Path $alertFile -Value ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
    Write-Status "ALERT $msg"
}
function Take-Shot([string]$name) {
    $remote = "/sdcard/_monitor.png"
    & $Adb -s $Serial shell screencap -p $remote 2>$null | Out-Null
    $local = Join-Path $shots ("{0}-{1}.png" -f (Get-Date -Format "HHmmss"), $name)
    & $Adb -s $Serial pull $remote $local 2>$null | Out-Null
    & $Adb -s $Serial shell rm $remote 2>$null | Out-Null
    if (Test-Path $local) { Write-Status "screenshot $local" }
}
function Parse-LogTime([string]$line) {
    if ($line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') {
        try {
            return [datetime]::ParseExact($matches[1], "MM-dd HH:mm:ss.fff", $null)
        } catch { }
    }
    return $null
}

# 持续抓 logcat(主日志 + crash)
$logProc = Start-Process -FilePath $Adb `
    -ArgumentList @("-s", $Serial, "logcat", "-T", "1", "-v", "time", "-s", "AutoSkip:V") `
    -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $out "logcat.err") `
    -PassThru -WindowStyle Hidden
$crashProc = Start-Process -FilePath $Adb `
    -ArgumentList @("-s", $Serial, "logcat", "-b", "crash", "-T", "1", "-v", "time") `
    -RedirectStandardOutput $crashFile -RedirectStandardError (Join-Path $out "crash.err") `
    -PassThru -WindowStyle Hidden

Write-Status "monitor start serial=$Serial duration=${DurationMinutes}min interval=${IntervalSeconds}s"
Write-Status "serial may drop; script will try adb connect"
# 没有设备时 adb logcat -c 会一直等设备,导致监控脚本卡死;
# 只在设备在线时清 crash 缓冲区。
$startState = (& $Adb -s $Serial get-state 2>$null | Out-String).Trim()
if ($startState -eq "device") {
    & $Adb -s $Serial logcat -b crash -c 2>$null | Out-Null
} else {
    Write-Status "device=$startState, will retry in loop"
}

$deadline = (Get-Date).AddMinutes($DurationMinutes)
$lastShotAt = @{}
$lastPeriodicShot = [datetime]::MinValue
$lastPssAt = [datetime]::MinValue
$lastBatteryAt = [datetime]::MinValue
$prevJiffies = $null
$prevCpuAt = $null
$lastSwipeAt = [datetime]::MinValue
$lastPageAt = [datetime]::MinValue
$lastPageLine = ""
$lastCrashSize = 0L
$lastHeavyAt = [datetime]::MinValue
$cachedTop = ""
$cachedAcc = ""
$lastOcrHitAt = [datetime]::MinValue
$lastMisfireAt = [datetime]::MinValue
$lastAlertedOcrHitAt = [datetime]::MinValue
$lastAlertedMisfireAt = [datetime]::MinValue

while ((Get-Date) -lt $deadline) {
    $now = Get-Date
    $state = (& $Adb -s $Serial get-state 2>$null | Out-String).Trim()
    if ($state -ne "device") {
        Write-Status "device=$state, reconnecting..."
        & $Adb connect $Serial 2>$null | Out-Null
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }

    # 熄屏/锁屏会让无障碍停止扫描，监控期间自动唤醒
    $wake = (& $Adb -s $Serial shell "dumpsys power | grep mWakefulness" 2>$null | Out-String)
    if ($wake -notmatch "mWakefulness=Awake") {
        & $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
        & $Adb -s $Serial shell wm dismiss-keyguard 2>$null | Out-Null
        Write-Status "woke device"
    }

    # dumpsys activity/accessibility 比较重,30 秒刷新一次即可;
    # get-state / 电量 / pid 仍然每轮检查。
    if (($now - $lastHeavyAt).TotalSeconds -ge 30) {
        $lastHeavyAt = $now
        $cachedTop = (& $Adb -s $Serial shell "dumpsys activity activities | grep -m1 mResumedActivity" 2>$null | Out-String).Trim()
        $cachedAcc = (& $Adb -s $Serial shell "dumpsys accessibility | grep -E 'Bound services|Crashed services'" 2>$null | Out-String).Trim()
    }
    $top = $cachedTop
    $acc = $cachedAcc
    $appPid = (& $Adb -s $Serial shell "pidof com.autoskip.ad" 2>$null | Out-String).Trim()

    # 切到桌面/微信/其它 App 后,上一条「广告页」日志已经失效,
    # 不能继续拿它算「卡广告」,否则会报几千秒的假告警。
    $targetTop = $top -match '(phoenix\.read|aweme|snssdk|kuaishou|com\.ss\.android|qqlive|iqiyi|youku|mgtv|bilibili)'
    if (-not $targetTop) {
        $lastPageLine = ""
        $lastPageAt = [datetime]::MinValue
    }

    $logTail = @()
    if (Test-Path $logFile) { $logTail = Get-Content $logFile -Tail 120 -Encoding UTF8 }
    $logText = ($logTail -join "`n")

    foreach ($line in $logTail) {
        if ($line -match '\[页面\]') {
            $t = Parse-LogTime $line
            if ($t) { $lastPageAt = $t; $lastPageLine = $line }
        }
        if ($line -match '\[上滑\]') {
            $t = Parse-LogTime $line
            if ($t) { $lastSwipeAt = $t }
        }
        if ($line -match '\[OCR\] 命中') {
            $t = Parse-LogTime $line
            if ($t -and $t -gt $lastOcrHitAt) { $lastOcrHitAt = $t }
        }
        if ($line -match '误滑|回退到第|检测到集数从|回退过头') {
            $t = Parse-LogTime $line
            if ($t -and $t -gt $lastMisfireAt) { $lastMisfireAt = $t }
        }
    }

    $cpuPct = ""
    if ($appPid) {
        $statLine = (& $Adb -s $Serial shell "cat /proc/$appPid/stat" 2>$null | Out-String).Trim()
        if ($statLine) {
            $parts = $statLine -split '\s+'
            if ($parts.Length -gt 14) {
                $jiffies = [double]$parts[13] + [double]$parts[14]
                if ($prevJiffies -ne $null -and $prevCpuAt -ne $null) {
                    $seconds = ($now - $prevCpuAt).TotalSeconds
                    if ($seconds -gt 0) {
                        $cpuPct = [math]::Round((($jiffies - $prevJiffies) / 100.0) / $seconds * 100.0, 1)
                    }
                }
                $prevJiffies = $jiffies
                $prevCpuAt = $now
            }
        }
    }

    $pss = ""
    if ($appPid -and ($now - $lastPssAt).TotalSeconds -ge 60) {
        $lastPssAt = $now
        $pss = (& $Adb -s $Serial shell "dumpsys meminfo $appPid | grep 'TOTAL PSS'" 2>$null | Out-String).Trim()
    }

    $battery = ""
    if (($now - $lastBatteryAt).TotalSeconds -ge 300) {
        $lastBatteryAt = $now
        $battery = (& $Adb -s $Serial shell "dumpsys battery | grep -E 'level|temperature'" 2>$null | Out-String).Trim().Replace("`r", "").Replace("`n", " ")
    }

    $hbLine = ($logTail | Select-String '\[心跳\]' | Select-Object -Last 1).Line
    # 只保留"页面类型 + 集数",不要把整行几百字示例写进 status.log
    $pageShort = "-"
    if ($lastPageLine -match '\[页面\]\s*([^|]+)') {
        $pageShort = $matches[1].Trim()
        if ($lastPageLine -match '集数=(第\d+集|未知)') {
            $pageShort = "$pageShort $($matches[1])"
        }
    } elseif ($hbLine -and $hbLine -match '页面=([^ ]+)') {
        $pageShort = $matches[1]
    }
    Write-Status ("top={0} | acc={1} | page={2} | cpu={3}% | pss={4} | battery={5}" -f `
        ($top -replace '.*mResumedActivity: ', ''), ($acc -replace '.*Bound services:', ''), $pageShort, $cpuPct, $pss, $battery)

    # 1) 无障碍服务被系统标记 crashed / 窗口拿不到
    if ($acc -match 'Crashed services:\{\{com\.autoskip\.ad') {
        Write-Alert "accessibility crashed"
        if (-not $lastShotAt.ContainsKey("crashed") -or ($now - $lastShotAt["crashed"]).TotalSeconds -ge 60) {
            $lastShotAt["crashed"] = $now
            Take-Shot "crashed"
        }
    }
    if ($logText -match '连续 20 秒拿不到活动窗口') {
        Write-Alert "root window null / accessibility abnormal"
    }

    # 2) 广告页停留超过 45 秒且没有上滑记录(只在目标视频 App 前台时判断)
    if ($targetTop -and $lastPageLine -match '广告页') {
        $stuckSeconds = if ($lastSwipeAt -gt $lastPageAt) { 0 } else { ($now - $lastPageAt).TotalSeconds }
        if ($stuckSeconds -gt 45) {
            Write-Alert ("ad page stuck {0:N0}s: {1}" -f $stuckSeconds, $pageShort) 120 "adstuck"
            if (-not $lastShotAt.ContainsKey("adstuck") -or ($now - $lastShotAt["adstuck"]).TotalSeconds -ge 60) {
                $lastShotAt["adstuck"] = $now
                Take-Shot "adstuck"
            }
        }
    }

    # 3) 误滑 / 回退:只对新增的那条日志告警,避免旧日志每轮重复报
    if ($lastMisfireAt -gt $lastAlertedMisfireAt) {
        $lastAlertedMisfireAt = $lastMisfireAt
        Write-Alert "episode misfire/correction detected" 120
        if (-not $lastShotAt.ContainsKey("misfire") -or ($now - $lastShotAt["misfire"]).TotalSeconds -ge 60) {
            $lastShotAt["misfire"] = $now
            Take-Shot "misfire"
        }
    }

    # 4) OCR 命中广告词:只有"命中后 6 秒内没有上滑"才算卡住,
    #    正常命中会马上上滑,不再逐条刷告警。
    if ($lastOcrHitAt -gt $lastAlertedOcrHitAt) {
        $hitAge = ($now - $lastOcrHitAt).TotalSeconds
        $swipedAfterHit = $lastSwipeAt -ge $lastOcrHitAt
        if ($hitAge -ge 6 -and -not $swipedAfterHit) {
            $lastAlertedOcrHitAt = $lastOcrHitAt
            Write-Alert "OCR ad hit without swipe" 120
            if (-not $lastShotAt.ContainsKey("ocrhit") -or ($now - $lastShotAt["ocrhit"]).TotalSeconds -ge 60) {
                $lastShotAt["ocrhit"] = $now
                Take-Shot "ocrhit"
            }
        } elseif ($swipedAfterHit) {
            # 命中后已上滑,属于正常流程,只记录不告警
            $lastAlertedOcrHitAt = $lastOcrHitAt
        }
    }

    # 5) crash log 只报本 App 的 FATAL/ANR(忽略 adb shell 的权限错误)
    if (Test-Path $crashFile) {
        $crashInfo = Get-Item $crashFile
        if ($crashInfo.Length -gt $lastCrashSize) {
            $lastCrashSize = $crashInfo.Length
            $newCrash = Get-Content $crashFile -Tail 120 -Encoding UTF8 -ErrorAction SilentlyContinue
            $ourCrash = $newCrash | Where-Object { $_ -match 'com\.autoskip\.ad|AutoSkip' }
            if (($ourCrash -join "`n") -match 'FATAL EXCEPTION|ANR in|am_anr') {
                Write-Alert "crash/ANR in crash buffer"
                Take-Shot "crash"
            }
        }
    }

    # 5) 每 5 分钟留一张现场截图
    if (($now - $lastPeriodicShot).TotalMinutes -ge 5 -and $top -match 'phoenix|aweme') {
        $lastPeriodicShot = $now
        Take-Shot "periodic"
    }

    Start-Sleep -Seconds $IntervalSeconds
}

Write-Status "monitor end"
Stop-Process -Id $logProc.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $crashProc.Id -Force -ErrorAction SilentlyContinue

