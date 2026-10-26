#Requires -Version 5.1
<#
.SYNOPSIS
    停止 code-association 服务（Windows，Windows PowerShell 5.1 及以上）。

.DESCRIPTION
    查找顺序：pid 文件 -> 端口反查（Get-NetTCPConnection）-> 命令行匹配 code-association.jar 兜底。

    关键点：不能杀完进程就返回。Spring Boot 的优雅关闭需要 1~3 秒，
    端口在这期间仍处于监听状态，紧接着启动新实例会撞上 "Port already in use"。
    所以这里会等到端口真正释放后再退出。

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

# 先优雅、再强制。
# 优雅的方式用 CloseMainWindow：它给进程的主窗口发 WM_CLOSE，JVM 会跑 shutdown hook，
# Spring 才能正常关连接池、刷盘。对没有窗口的进程（java.exe 在隐藏窗口里运行）它返回
# False，此时只能 Stop-Process：Windows 没有 POSIX 的 SIGTERM，TerminateProcess 不执行
# shutdown hook，所以只要有一线可能就先走优雅路径，并给 3 秒让它自己退。
# 变量名不能叫 $PID：那是 PowerShell 的自动变量（当前进程 PID），覆盖它会让脚本杀错进程。
function Stop-ServiceProcess {
    param([int]$TargetId)

    $targetProcess = Get-Process -Id $TargetId -ErrorAction SilentlyContinue
    if (-not $targetProcess) {
        Write-Host "进程不存在 (PID $TargetId)"
        return
    }

    # CloseMainWindow 对隐藏窗口/无窗口的 java.exe 基本都返回 False，
    # 所以不能"没信号就直接强杀"：仍然给 3 秒，让它在收到 WM_CLOSE 或自行收尾时体面退出。
    # 这段等待是刻意无条件的，代价最多 3 秒，换来的是尽量不打断 Spring 的关闭流程。
    $signaled = $false
    try { $signaled = $targetProcess.CloseMainWindow() } catch { $signaled = $false }

    if ($signaled) {
        Write-Host "已发送关闭信号 (PID $TargetId)，等待优雅退出…"
    } else {
        Write-Host "进程无窗口，无法发送关闭信号，先等待 3 秒再强制结束…"
    }

    # Wait-Process 超时会抛错，这里吞掉，交给下面的强制结束处理。
    try { Wait-Process -Id $TargetId -Timeout 3 -ErrorAction Stop } catch { }

    # Refresh / HasExited 在进程刚好消失时可能抛异常（句柄已失效），
    # 这里一律当作"已经退出"，不要让收尾逻辑把脚本搞崩。
    $stillRunning = $true
    try {
        $targetProcess.Refresh()
        $stillRunning = -not $targetProcess.HasExited
    } catch {
        $stillRunning = $false
    }

    if ($stillRunning) {
        Write-Host "强制结束 (PID $TargetId)"
        Stop-Process -Id $TargetId -Force -ErrorAction SilentlyContinue
        try { $targetProcess.WaitForExit(5000) | Out-Null } catch { }
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
if ($targets.Count -eq 0) {
    foreach ($ownerId in (Get-PortOwnerPid -PortNumber $portNumber)) { $targets.Add($ownerId) }
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
