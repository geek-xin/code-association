#Requires -Version 5.1
<#
.SYNOPSIS
    停止 code-association 服务（Windows，Windows PowerShell 5.1 及以上）。

.DESCRIPTION
    查找顺序：pid 文件 -> 端口反查（Get-NetTCPConnection）-> 命令行匹配 code-association.jar 兜底。

    关键点：不能杀完进程就返回。进程退出与端口真正释放之间有时间差，
    紧接着启动新实例会撞上 "Port already in use"。所以这里会等到端口真正释放后再退出。

    与 stop.sh 的差别：POSIX 用 SIGTERM，JVM 会跑 shutdown hook；Windows 没有等价信号，
    只能 TerminateProcess（shutdown hook 不执行）。对本服务无影响，理由见 Stop-ServiceProcess 上方。

        .\stop.ps1                # 默认 9996 端口
        .\stop.ps1 8080           # 位置参数指定端口
        .\stop.ps1 -Port 8080     # 命名参数指定端口
        $env:PORT = 8080          # 环境变量方式

    本文件必须保存为 UTF-8 with BOM + CRLF（原因见 start.ps1 顶部说明）。
#>
[CmdletBinding()]
param(
    # 位置 0，保证 stop.bat 的 %* 透传（stop.bat 8080）能落到端口上。
    [Parameter(Position = 0)]
    [string]$Port = $env:PORT
)

$ErrorActionPreference = 'Stop'

# 脚本级"停止失败"标记：Stop-ServiceProcess 只能 return，无法直接把退出码带出去。
$script:stopFailed = $false

$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptRoot)) { $scriptRoot = (Get-Location).Path }
Set-Location -LiteralPath $scriptRoot

if ([string]::IsNullOrWhiteSpace($Port)) { $Port = '9996' }

$portNumber = 0
if (-not [int]::TryParse($Port.Trim(), [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) {
    Write-Host "端口无效：$Port（应为 1-65535 的整数）"
    exit 1
}

$pidFile = Join-Path $scriptRoot '.code-association.pid'

# 端口是否仍被监听。Get-NetTCPConnection 需要 Windows 8 / Server 2012 以上；
# 老系统上退化为直接尝试 TCP 连接。
function Test-PortListening {
    param([int]$PortNumber)

    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        try {
            $connections = Get-NetTCPConnection -LocalPort $PortNumber -State Listen -ErrorAction Stop
            if ($connections) { return $true }
        } catch {
            return $false
        }
        return $false
    }

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $PortNumber, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne(300)) {
            $client.EndConnect($async)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# pid 文件里的 PID 会被系统回收复用，杀之前必须确认它真的是本服务的 java，
# 否则可能把恰好复用了这个 PID 的无关进程干掉。
function Test-ServicePid {
    param([int]$TargetId)

    try {
        $cimProcess = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $TargetId" -ErrorAction Stop
        if (-not $cimProcess) {
            # 查不到这个 PID，说明进程已经退出（PID 失效）
            return $false
        }
        if ($cimProcess.CommandLine) {
            return ($cimProcess.CommandLine -like '*code-association.jar*')
        }
    } catch {
        # 拿不到命令行时保守放行：用户既然要求停止，就按 pid 文件执行。
    }
    return $true
}

# 按端口找监听进程。这是最可靠的反查方式：pid 文件丢了、被手工删了，
# 或者上次是从别的目录启动的，都还能凭端口找到真正在跑的进程。
function Get-PortOwnerPid {
    param([int]$PortNumber)

    $owners = New-Object System.Collections.Generic.List[int]
    if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { return $owners }

    try {
        $connections = Get-NetTCPConnection -LocalPort $PortNumber -State Listen -ErrorAction Stop
        foreach ($connection in $connections) {
            if (-not $connection.OwningProcess -or $connection.OwningProcess -le 0) { continue }
            $ownerId = [int]$connection.OwningProcess
            # start.ps1 通过 cmd /c 重定向启动 java，cmd 与 java 是两个进程；
            # 监听端口的一定是 java，这里只收 java，避免把 cmd 外壳当服务进程。
            $ownerProcess = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
            if ($ownerProcess -and $ownerProcess.ProcessName -eq 'cmd') { continue }
            $owners.Add($ownerId)
        }
    } catch {
        # 没有监听就是没有监听，交给调用方判断
    }
    return $owners
}

# 最后的兜底：按命令行匹配 jar 名，覆盖"端口被改过、pid 文件也没了"的情况。
function Get-JarProcessId {
    $owners = New-Object System.Collections.Generic.List[int]

    try {
        $javaProcesses = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop
        foreach ($javaProcess in $javaProcesses) {
            if ($javaProcess.CommandLine -and ($javaProcess.CommandLine -like '*code-association.jar*')) {
                $owners.Add([int]$javaProcess.ProcessId)
            }
        }
    } catch {
        # Win32_Process 查询失败（极少见）时只能依赖前两种方式
    }
    return $owners
}

# Windows 没有 POSIX 的 SIGTERM：TerminateProcess 不执行 JVM 的 shutdown hook。
# 但 java.exe 是控制台程序，控制台窗口归 conhost.exe 所有，MainWindowHandle 恒为 0，
# CloseMainWindow() 因此永远返回 False —— 对 java 走这条"优雅"路径从来没有成功过，
# 代价却是每次停止都白等 3 秒，还打印一句"进程无窗口"让人以为出了问题。
#
# 现在先看窗口句柄：有主窗口（包装器或别的启动方式）才尝试投递关闭信号，
# 没有就直接结束进程。走了哪条路用 -Verbose 看，默认输出只留结果。
#
# 直接结束对本服务是可接受的，因为这里没有"关到一半"的状态：
#   - 应用代码没有注册任何 shutdown hook（也没有 @PreDestroy）；
#   - Spring 自带的关闭钩子只做上下文关闭，而它在 TerminateProcess 下同样不会执行，
#     也就是说"优雅"与"直接结束"实际做的事没有区别；
#   - SQLite 用默认的 rollback journal，进程被杀后下次连接自动回滚，不会留下半个事务；
#   - 被中断的分析在下次启动时由 recoverStaleAnalyses 标记为失败，不会永远停在 RUNNING。

# 三态：Running / Exited / Unknown。用三态而不是布尔，是因为"读不到状态"既不能当成
# "还在跑"（会误报失败、白等），也不能当成"已经停了"（会谎报成功）。
function Get-ProcessState {
    param([System.Diagnostics.Process]$TargetProcess)

    try {
        $TargetProcess.Refresh()
        if ($TargetProcess.HasExited) { return 'Exited' }
        return 'Running'
    } catch {
        # 句柄已失效：绝大多数情况是进程刚好退出了，但脚本无法证明这一点。
        return 'Unknown'
    }
}

# 变量名不能叫 $PID：那是 PowerShell 的自动变量（当前进程 PID），覆盖它会让脚本杀错进程。
function Stop-ServiceProcess {
    param([int]$TargetId)

    $targetProcess = Get-Process -Id $TargetId -ErrorAction SilentlyContinue
    if (-not $targetProcess) {
        Write-Host "进程不存在 (PID $TargetId)"
        return
    }

    # MainWindowHandle 是 IntPtr，与 0 比较在 PowerShell 里能正确得到 Boolean；
    # 拿不到句柄（权限不足、进程正在退出）时按"没有窗口"处理。
    $hasWindow = $false
    try { $hasWindow = ($targetProcess.MainWindowHandle -ne 0) } catch { $hasWindow = $false }

    if ($hasWindow) {
        $signaled = $false
        try { $signaled = $targetProcess.CloseMainWindow() } catch { $signaled = $false }
        if ($signaled) {
            Write-Host "已发送关闭信号 (PID $TargetId)，等待优雅退出…"
            # Wait-Process 超时会抛错，这里吞掉，交给下面的强制结束处理。
            try { Wait-Process -Id $TargetId -Timeout 3 -ErrorAction Stop } catch { }
        } else {
            Write-Verbose "PID $TargetId 有主窗口但没有接受关闭信号，改为结束进程"
        }
    } else {
        Write-Verbose "PID $TargetId 是控制台进程（无主窗口），Windows 无法投递关闭信号，直接结束进程"
    }

    # 用 HasExited 判定，不要用"刚才发过信号"来推断。HasExited 绑定在已打开的句柄上，
    # 即使目标退出后 PID 被系统复用，也不会把新进程误判成本进程。
    # 读不到状态时既不当成"还在跑"，也不当成"已经停了"，而是交给下面的 Unknown 分支。
    $state = Get-ProcessState -TargetProcess $targetProcess

    if ($state -eq 'Running') {
        Write-Verbose "结束进程 (PID $TargetId)"
        # 优先用句柄上的 Kill()：它在打开句柄的那一刻就绑定了目标，
        # 而 Stop-Process -Id 会在"取句柄"和"发终止"之间按 PID 重新解析一次，
        # 恰好在这个窗口里目标退出、PID 又被复用，就会杀掉无关的新进程。
        try {
            $targetProcess.Kill()
        } catch {
            # 句柄方式失败（权限不足、进程已退出）时退回 Stop-Process，
            # 它还能覆盖"进程属于别的用户"这类需要走不同路径的情形。
            Stop-Process -Id $TargetId -Force -ErrorAction SilentlyContinue
        }
        try { $targetProcess.WaitForExit(5000) | Out-Null } catch { }
        $state = Get-ProcessState -TargetProcess $targetProcess

        if ($state -eq 'Running') {
            Write-Host "无法结束进程 (PID $TargetId)：可能权限不足（试试以管理员身份运行）"
            $script:stopFailed = $true
            return
        }
    }

    if ($state -eq 'Unknown') {
        # 唯一无法证明"真的停了"的分支：不谎报成功，也不谎报失败。
        Write-Host "已发出结束请求 (PID $TargetId)，但无法确认进程状态，请用 netstat -ano 复查"
        return
    }

    Write-Host "已停止 (PID $TargetId)"
}

# ---------- 1. 定位要停的进程 ----------
$targets = New-Object System.Collections.Generic.List[int]

if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
    $rawLine = $null
    try { $rawLine = @(Get-Content -LiteralPath $pidFile -TotalCount 1 -ErrorAction Stop)[0] } catch { $rawLine = $null }

    $filePid = 0
    if ($null -ne $rawLine -and [int]::TryParse(([string]$rawLine).Trim(), [ref]$filePid)) {
        if (-not (Get-Process -Id $filePid -ErrorAction SilentlyContinue)) {
            Write-Host "进程不存在 (PID $filePid)"
        } elseif (-not (Test-ServicePid -TargetId $filePid)) {
            Write-Host "PID $filePid 不是本服务（可能 PID 被系统复用），改为按端口查找"
        } else {
            $targets.Add($filePid)
        }
    }
}

# pid 文件没给出可用 PID 时按端口反查，与 POSIX 版用 lsof 反查是同一个思路。
#
# 端口上是谁，脚本并不知道：这里必须像 POSIX 版（stop.sh 的 pid_is_ours）一样确认
# 它真的是本服务，否则 stop.bat 8080 会把恰好占用 8080 的无关程序杀掉。
# 确认不了（拿不到命令行，通常是权限不足）时按本服务处理——用户既然点名了这个端口，
# 拒绝执行反而让"停不掉"变成新的问题；但这种情况会明确说出来。
if ($targets.Count -eq 0) {
    foreach ($ownerId in (Get-PortOwnerPid -PortNumber $portNumber)) {
        if (Test-ServicePid -TargetId $ownerId) {
            $targets.Add($ownerId)
        } else {
            Write-Host "跳过 PID $ownerId：它占用端口 $portNumber 但不是本服务"
        }
    }
}

# 端口上也找不到时，最后按命令行兜底。
if ($targets.Count -eq 0) {
    foreach ($ownerId in (Get-JarProcessId)) { $targets.Add($ownerId) }
}

if ($targets.Count -eq 0) {
    Write-Host "没有正在运行的服务"
} else {
    foreach ($targetId in ($targets | Select-Object -Unique)) {
        Stop-ServiceProcess -TargetId $targetId
    }
}

# pid 文件此时已经没用了，先清掉，避免下次启动被过期 PID 干扰。
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue

# ---------- 2. 等端口真正释放 ----------
# 最多等 10 秒（40 次 x 250ms），与 POSIX 版一致。
$released = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    if (-not (Test-PortListening -PortNumber $portNumber)) {
        $released = $true
        break
    }
    Start-Sleep -Milliseconds 250
}

if ($released) {
    Write-Host "端口 $portNumber 已释放"
    # 端口释放不代表"该停的都停了"：进程可能还活着，只是不再监听这个端口。
    # 退出码是自动化（stop.bat && start.bat、CI、服务包装器）唯一能看到的信号，
    # 所以结束失败必须以非 0 退出，否则它们会以为服务已经关了。
    if ($script:stopFailed) { exit 1 }
    exit 0
}

Write-Host "警告：端口 $portNumber 仍被占用"
foreach ($ownerId in (Get-PortOwnerPid -PortNumber $portNumber)) {
    $ownerProcess = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
    if ($ownerProcess) {
        Write-Host "  监听进程 PID $ownerId ($($ownerProcess.ProcessName))"
    } else {
        Write-Host "  监听进程 PID $ownerId"
    }
}
exit 1
