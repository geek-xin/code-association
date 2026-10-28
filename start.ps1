#Requires -Version 5.1
<#
.SYNOPSIS
    启动 code-association 服务（Windows，Windows PowerShell 5.1 及以上）。

.DESCRIPTION
    用法与 POSIX 版 start.sh 对齐：

        .\start.ps1                # 默认 9996 端口，数据目录 .\.code-association
        .\start.ps1 8080           # 位置参数指定端口
        .\start.ps1 -Port 8080     # 命名参数指定端口
        $env:PORT = 8080           # 环境变量方式

    环境变量：PORT / DATA_DIR / LOG_FILE。

    本文件必须保存为 UTF-8 with BOM + CRLF：Windows PowerShell 5.1 会把没有 BOM 的脚本
    按当前 ANSI 代码页解码，中文注释会变成乱码，甚至把中文当成标识符而直接语法报错。
#>
[CmdletBinding()]
param(
    # 位置 0 是刻意安排的：start.bat 只是把参数原样透传（%*），
    # 有了 Position=0，start.bat 8080 才能落到端口上；
    # 优先级与 POSIX 版一致：显式参数 > 环境变量 > 默认 9996。
    [Parameter(Position = 0)]
    [string]$Port = $env:PORT,

    [string]$DataDir = $env:DATA_DIR,

    [string]$LogFile = $env:LOG_FILE
)

# 等价于 bash 的 set -e：任何未捕获的错误都终止脚本，避免带着半启动状态继续往下走。
$ErrorActionPreference = 'Stop'

# 双击运行时当前目录是 System32 之类的无关位置，所以所有路径都以脚本所在目录为基准，
# 等价于 POSIX 版的 cd 到脚本目录。
$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptRoot)) { $scriptRoot = (Get-Location).Path }
Set-Location -LiteralPath $scriptRoot

if ([string]::IsNullOrWhiteSpace($Port)) { $Port = '9996' }
if ([string]::IsNullOrWhiteSpace($DataDir)) { $DataDir = './.code-association' }
if ([string]::IsNullOrWhiteSpace($LogFile)) { $LogFile = './logs/console.log' }

$portNumber = 0
if (-not [int]::TryParse($Port.Trim(), [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) {
    Write-Host "端口无效：$Port（应为 1-65535 的整数）"
    exit 1
}

$pidFile = Join-Path $scriptRoot '.code-association.pid'
$jarPath = Join-Path (Join-Path $scriptRoot 'target') 'code-association.jar'

# DATA_DIR / LOG_FILE 允许写相对路径（相对脚本目录）或绝对路径（如 D:\data），
# 这里统一转成绝对路径再交给 java，免得 java 的工作目录不同导致数据写错地方。
function Resolve-ScriptPath {
    param([string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $scriptRoot $Path))
}

# 参数值一律套双引号，保证含空格的路径（C:\Program Files\...）能完整传给 java。
# 注意不能只做简单拼接：如果值以反斜杠结尾，命令行会把它和收尾引号组成 \" 而被
# 解析成字面引号。这里统一去掉末尾多余的分隔符（盘符根目录 D:\ 这类保留），
# 避免出现"路径少一个字符"这种极难排查的问题。
function Quote-ArgValue {
    param([string]$Value)

    $trimmed = $Value
    while ($trimmed.Length -gt 3 -and $trimmed.EndsWith('\')) {
        $trimmed = $trimmed.Substring(0, $trimmed.Length - 1)
    }
    return ('"' + $trimmed + '"')
}

$dataDirPath = Resolve-ScriptPath -Path $DataDir
$logPath = Resolve-ScriptPath -Path $LogFile

# stdout 与 stderr 必须落在两个不同的文件里（原因见下面 Start-Process 处的注释），
# 约定把 stderr 放在同一目录、同名加 .err 后缀，默认即 logs/console.err.log。
# 这两份都是「进程原始输出」（每次启动截断），不是应用日志：logback 初始化之前的报错、
# JVM 崩溃输出只在这里；应用日志由 logback 写到 logs/code-association.log（保留 7 天）。
$logDirectory = Split-Path -Parent $logPath
if (-not [string]::IsNullOrWhiteSpace($logDirectory) -and -not (Test-Path -LiteralPath $logDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
}
$errLogPath = Join-Path $logDirectory ([System.IO.Path]::GetFileNameWithoutExtension($logPath) + '.err.log')

# 端口是否已被监听。Get-NetTCPConnection 需要 Windows 8 / Server 2012 以上；
# 老系统上没有这个 cmdlet 时退化为直接尝试 TCP 连接。
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

# pid 文件里的 PID 会被系统回收复用，所以除了"这个 PID 还活着"，
# 还要确认它确实是本服务的 java 进程，否则会误判成"已在运行"而拒绝启动。
function Test-ServicePid {
    param([int]$TargetId)

    if (-not (Get-Process -Id $TargetId -ErrorAction SilentlyContinue)) { return $false }

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
        # 拿不到命令行（权限不足等）时保守处理：认为它还在跑，让用户自己先 stop。
    }
    return $true
}

# 日志尾部：启动失败时唯一有用的排查线索。java 正持有文件句柄，
# 读取仍应成功，但这里单独兜一层，不让"打印日志"这一步掩盖真正的错误。
function Show-LogTail {
    param(
        [string]$Path,
        [string]$Label = '日志',
        [int]$Lines = 20
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    Write-Host "--- $Label 尾部（$Path）---"
    try {
        Get-Content -LiteralPath $Path -Tail $Lines -ErrorAction Stop | ForEach-Object { Write-Host $_ }
    } catch {
        Write-Host "（无法读取 $Path：$($_.Exception.Message)）"
    }
}

# ---------- 1. 是否已经在运行 ----------
if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
    $rawLine = $null
    try { $rawLine = @(Get-Content -LiteralPath $pidFile -TotalCount 1 -ErrorAction Stop)[0] } catch { $rawLine = $null }

    $existingId = 0
    if ($null -ne $rawLine -and [int]::TryParse(([string]$rawLine).Trim(), [ref]$existingId)) {
        if (Test-ServicePid -TargetId $existingId) {
            Write-Host "已在运行 (PID $existingId)，先执行 .\stop.ps1"
            exit 1
        }
        Write-Host "pid 文件已过期（PID $existingId 已不是本服务），继续启动"
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

# pid 文件可能被手工删掉，或上次进程异常退出，所以再按端口确认一次：
# 端口已被占用时直接失败，比让 java 自己抛 BindException、再干等 60 秒清楚得多。
if (Test-PortListening -PortNumber $portNumber) {
    Write-Host "端口 $portNumber 已被占用，先执行 .\stop.ps1"
    exit 1
}

# ---------- 2. jar 与 java ----------
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    Write-Host "未找到 target\code-association.jar，先构建："
    Write-Host "  mvn -q -DskipTests package"
    exit 1
}

# 查找顺序：JAVA_HOME\bin\java.exe 优先（用户显式指定的 JDK 版本更可信），
# 找不到再退回 PATH 里的 java，与 POSIX 版一致。
$javaExe = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaCandidate = Join-Path (Join-Path $env:JAVA_HOME 'bin') 'java.exe'
    if (Test-Path -LiteralPath $javaCandidate -PathType Leaf) { $javaExe = $javaCandidate }
}
if (-not $javaExe) {
    $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaFirst = @($javaCommand)[0]
        if ($javaFirst -and $javaFirst.Path) { $javaExe = $javaFirst.Path }
    }
}
if (-not $javaExe) {
    Write-Host "未找到 java.exe：请安装 JDK 21+，或设置 JAVA_HOME 指向 JDK 安装目录。"
    Write-Host "  已尝试：JAVA_HOME\bin\java.exe、PATH 中的 java"
    exit 1
}

# ---------- 3. 后台启动 ----------
# 这里刻意用 cmd.exe 做重定向外壳，而不是 Start-Process -RedirectStandardOutput，原因：
#
#   Start-Process 的重定向在 Windows PowerShell 5.1 上是"管道"实现的：父进程（本脚本）
#   打开日志文件，把子进程的 stdout/stderr 通过匿名管道读出来再写进文件。脚本一退出，
#   读端就没了，java 后续所有日志写入都会失败——服务还在跑，但日志从启动完成那一刻起
#   就不再增长，排查问题时几乎没有线索。
#   POSIX 版 start.sh 是把文件描述符直接交给 java（os.dup2），脚本退出后日志照写。
#   要做到同样的行为，就必须让 cmd.exe 打开日志文件、把句柄继承给 java：
#   cmd 的重定向（> 与 2>）正是这么做的，句柄由 java 持有，与启动脚本的生死无关。
#
#   代价是：Start-Process 返回的是 cmd.exe 的 PID，不是 java 的 PID。所以 pid 文件
#   不写这个 PID，而是像 start.sh 那样"按端口反查真实监听进程"（见下面 Get-ServicePidByPort），
#   反查不到就不写——stop.ps1 还有端口与命令行两级兜底。
#
# 为什么用 Start-Process 而不是 Start-Job / 后台作业：
# PowerShell 的后台作业属于当前会话，脚本一退出作业就被连带终止，服务活不过启动脚本；
# Start-Process 创建的是独立的 Windows 进程，脚本退出后它继续运行（Windows 没有 POSIX
# 那种会话/进程组连带清理，这正是我们想要的效果，也就不需要 setsid + 双 fork 那套技巧）。
$comspec = $env:ComSpec
if ([string]::IsNullOrWhiteSpace($comspec)) {
    $comspec = Join-Path (Join-Path $env:SystemRoot 'System32') 'cmd.exe'
}
if (-not (Test-Path -LiteralPath $comspec -PathType Leaf)) {
    Write-Host "未找到 cmd.exe（%ComSpec% 无效）：$comspec"
    exit 1
}

$javaCommandLine = (Quote-ArgValue -Value $javaExe) + ' -jar ' + (Quote-ArgValue -Value $jarPath) +
    ' --server.port=' + $portNumber +
    ' --code-association.data-dir=' + (Quote-ArgValue -Value $dataDirPath)

# > 覆盖 stdout，2> 覆盖 stderr。cmd 不允许同一个文件被两个重定向同时打开（会互相覆盖），
# 所以 stderr 单独写到 logs/console.err.log，与 README / 故障排查文档的说法一致。
$redirectedCommand = $javaCommandLine + ' > ' + (Quote-ArgValue -Value $logPath) +
    ' 2> ' + (Quote-ArgValue -Value $errLogPath)

# /d 跳过 AutoRun，/s 配合外层引号让 cmd 按原样解析整条命令，/c 执行完即退出。
$cmdArguments = '/d /s /c "' + $redirectedCommand + '"'

# 用哈希表 splatting 而不是反引号续行：反引号后面只要多一个空格就断行失败，
# 这种隐形错误在 5.1 上很难排查。
$startProcessParameters = @{
    FilePath     = $comspec
    ArgumentList = $cmdArguments
    WorkingDirectory = $scriptRoot
    WindowStyle  = 'Hidden'
    PassThru     = $true
}
$started = Start-Process @startProcessParameters

# 按端口反查真正在监听的 java 进程 PID（与 start.sh 用 lsof 反查同一个思路）。
# Get-NetTCPConnection 需要 Windows 8 / Server 2012 以上，没有时退回命令行匹配。
function Get-ServicePidByPort {
    param([int]$PortNumber)

    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        try {
            $connections = Get-NetTCPConnection -LocalPort $PortNumber -State Listen -ErrorAction Stop
            foreach ($connection in $connections) {
                if ($connection.OwningProcess -and $connection.OwningProcess -gt 0) {
                    return [int]$connection.OwningProcess
                }
            }
        } catch {
            # 端口还没监听，继续等
        }
        return 0
    }

    # 兜底：在 java.exe 的命令行里找本次启动的端口，确认是同一个实例。
    try {
        $javaProcesses = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop
        foreach ($javaProcess in $javaProcesses) {
            if ($javaProcess.CommandLine -and
                ($javaProcess.CommandLine -like '*code-association.jar*') -and
                ($javaProcess.CommandLine -like ('*--server.port=' + $PortNumber + '*'))) {
                return [int]$javaProcess.ProcessId
            }
        }
    } catch {
        # 查不到就返回 0，交给调用方继续等
    }
    return 0
}

# ---------- 4. 等待健康检查 ----------
Write-Host "正在启动，等待 http://127.0.0.1:$portNumber/api/health 就绪…"

# 最多等 60 秒，每 0.5 秒探一次；顺便把真实 PID 写进 pid 文件（端口一监听就能拿到）。
# -UseBasicParsing：5.1 默认走 IE 引擎解析 HTML，第一次可能卡在 IE 初始化上，加上它可避开。
$ready = $false
$servicePid = 0
$processExited = $false
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    if ($servicePid -eq 0) {
        $servicePid = Get-ServicePidByPort -PortNumber $portNumber
        if ($servicePid -gt 0) {
            # 启动过程中就把 pid 落盘，用户随时可以用 stop.ps1 中止
            Set-Content -LiteralPath $pidFile -Value $servicePid -Encoding ASCII
        }
    }

    # cmd /c 会一直等到 java 退出，所以它退出就意味着 java 也退了（或压根没起来）。
    # HasExited 在句柄失效时会抛异常，一律按"已退出"处理。
    try { $processExited = $started.HasExited } catch { $processExited = $true }
    if ($processExited) { break }

    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$portNumber/api/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # 服务还没起来，继续等
    }
    Start-Sleep -Milliseconds 500
}

if (-not $ready) {
    if ($processExited) {
        # ExitCode 在极端情况下（句柄已释放）会抛异常，取不到就写"未知"，别让它盖住真正的原因。
        $exitCodeText = '未知'
        try { $exitCodeText = [string]$started.ExitCode } catch { }
        Write-Host "启动失败，进程已退出（退出码 $exitCodeText）。日志尾部："
        Show-LogTail -Path $logPath -Label '日志'
        Show-LogTail -Path $errLogPath -Label '错误日志'
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        exit 1
    }

    # 超时不主动杀进程：可能只是机器慢，进程还在初始化，留给用户决定（与 POSIX 版一致）。
    Write-Host "启动超时（60 秒），请查看日志：$logPath"
    Show-LogTail -Path $logPath -Label '日志'
    Show-LogTail -Path $errLogPath -Label '错误日志'
    exit 1
}

if ($servicePid -gt 0) {
    Write-Host "已启动：http://127.0.0.1:$portNumber  (PID $servicePid)"
} else {
    # 反查不到 PID 不影响服务运行，stop.ps1 会改用端口 / 命令行定位。
    Write-Host "已启动：http://127.0.0.1:$portNumber  (PID 未知，停止时按端口查找)"
}
Write-Host "进程输出：$logPath"
Write-Host "进程错误输出：$errLogPath"
Write-Host "应用日志：logs/code-association.log（ERROR 另见 logs/code-association-error.log）"
Write-Host "停止：.\stop.ps1 $portNumber"
exit 0
