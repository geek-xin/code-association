# 变更记录

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.3.0] - 2026-10-26

本次只改「外壳」：jar 里的分析能力一行没动，补的是启动/停止脚本的平台覆盖。

### 新增

- **Windows 启停脚本**：`start.bat` / `stop.bat`（薄封装，自带 `-ExecutionPolicy Bypass`）
  与 `start.ps1` / `stop.ps1`（PowerShell 实现）。用 `Start-Process` 隐藏窗口启动
  `cmd /c` 外壳，由 cmd 的重定向把 `code-association.log` / `code-association.err.log`
  的句柄直接交给 java，脚本退出后日志照常写入；启动完成后按端口反查真实 java 进程
  PID 写入 `.code-association.pid`。停止时按 pid 文件 → 端口（`Get-NetTCPConnection`）
  → 命令行匹配逐级兜底，先等优雅关闭再强制结束。
- `release.sh` 打包时一并收入两个平台的六个脚本，并在发布前做字节级校验：
  脚本是否齐全、`.ps1` 是否带 UTF-8 BOM、`.bat` 是否纯 ASCII、是否统一 CRLF。

### 变更

- **`start.sh` / `stop.sh` 改成 Linux 与 macOS 通用**：不再硬依赖 `python3`、`lsof`、
  `pgrep`、`seq`、`curl`，改为逐项探测后按可用性选实现。
  - 脱离终端：Linux 用 `setsid`；macOS（无 `setsid`）用 `python3` 双 fork；
    两者都没有时退化为 `nohup` 并明确提示。
  - 健康检查：优先 `curl`，否则 bash `/dev/tcp` 探测。
  - 查监听进程：`lsof` → `ss` → `netstat` → 记录的 PID / 命令行匹配。
  - 数字循环改用 bash 算术 for，去掉 `seq` 依赖；保持 bash 3.2（macOS 自带版本）兼容。
- `stop.sh` 增加可选端口参数（`./stop.sh 8080`），与 `start.sh` 的参数契约对齐。
- 命令行契约不变：`./start.sh`、`./start.sh 8080`、`PORT` / `DATA_DIR` / `LOG_FILE` 环境变量。

### 修复

- Linux 上没有 `python3` 或 `lsof` 的机器无法启动/停止服务的问题。
- Windows 用户此前只能前台 `java -jar` 运行，关掉命令行窗口服务就没了。

### 已知边界

- Windows 脚本只做了静态审查与字节级编码校验，**未在真实 Windows 机器上运行验证**；
  Linux 路径已验证。遇到问题请带日志提 issue。

## [0.2.1] - 2026-10-25

### 修复

- **定时扫描会全量重扫**：分析实际比较的提交没有落库，调度无法区分"已分析过"与"从未分析"，
  每天把所有程序重扫一遍（`scanned=4 / skipped=0`）。现在完成时写回 base/head commit。
- **孤儿任务阻塞调度**：进程被杀时分析卡在 `RUNNING`，后续扫描永远把它当"忙碌"。
  启动时恢复为失败，让调度自愈。
- **位置丢失的程序反复重试**：目录被删除后每次扫描都失败。现在跳过并说明原因。
- **`/api/analyses` 超时**：列表查询用了 `SELECT *`，会读取几 MB 的 `report_json`，
  接口 60 秒不返回。改为显式列，降到 21ms。

### 新增

- 每日定时扫描：默认 02:30，只对产生新提交的程序发起分析。
  配置项 `code-association.schedule.*`，运维接口 `GET /api/schedule`、`POST /api/schedule/run`。
- 定时扫描测试：首次扫描、无新提交跳过、位置丢失跳过、孤儿恢复。

## [0.2.0] - 2026-10-25

### 新增

- 每日定时扫描（`ScheduledScanService`、`@EnableScheduling`、`AppProperties.Schedule`）。

### 变更

- **扫描范围收窄为只分析其他程序**：引擎不再对自身索引做传播，报告的模块地图、
  盲区与计数全部来自对方程序。`ReportBuilder` 删除了本程序回退分支。
- 命令行 `--peer` 改为必填，缺少时报错退出（码 2），避免 `--fail-on` 在空报告上静默通过。

## [0.1.0] - 2026-10-25

### 新增

- 首次发布：语义变更识别、代码知识图谱、跨程序契约匹配（HTTP / 数据库 / 配置 /
  Maven / 消息）、影响传播、风险与置信度评分、Web 界面、命令行与 CI 集成。
