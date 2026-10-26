# 变更记录

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.4.1] - 2026-10-26

### 新增

- **左侧菜单栏可展开 / 收起**：设置页右上角与侧栏头部各有一个折叠按钮，
  也可以按 `Ctrl/⌘ + B`。收起后文字菜单折起、只留图标栏，内容区拿回约 220px 宽度；
  展开恢复完整菜单。选择记在浏览器本地，刷新后保持。
  - 窄屏（≤860px）默认收起，但**仍然可以手动展开**——此前该断点直接用
    `display:none` 把菜单藏死，没有任何展开入口。
  - 收起时用 `visibility:hidden` 而不只是零宽度，避免键盘 Tab 停在看不见的控件上；
    两个按钮的 `aria-expanded` 同步反转。
  - 快捷键在输入框 / 文本域 / 下拉框里让位，不会吃掉正常输入。

### 修复

- **重启提示用版本号判断会误判**：`apply` 后要提示「已替换 jar，请重启服务生效」，
  原判据是「当前版本 == 已应用版本」。但 JVM 一直报告它启动时的版本，所以
  「重新应用正在跑的版本」会被当成「已经重启过」，提示当场消失。改用进程启动时间作判据：
  `status` 返回 `startedAt`，`apply` / `rollback` 返回 `swappedAt`（都是服务端毫秒数），
  只有 `startedAt > swappedAt` 才算重启完成。
- `release.sh` 的 jar 校验被 SIGPIPE 误判为失败：`unzip -Z1 ... | grep -q` 在
  `pipefail` 下是错的——`grep -q` 命中后立即退出，`unzip` 收到 SIGPIPE 被杀，
  整条管道返回 141，于是「校验通过」被当成失败。改为先把清单读进变量再 grep。

## [0.4.0] - 2026-10-26

### 新增

- **版本检查与自动更新**：服务定时向 GitHub 查询 release，设置页直接显示当前版本、
  最新版本与**历史版本列表**（含每个版本的发布说明与下载入口）。
  - `GET /api/update` 读状态（带缓存，命中缓存不消耗 GitHub 额度）；
    `POST /api/update/check` 强制刷新。
  - `POST /api/update/download` 下载指定版本的 jar 到 `{data-dir}/updates/`，
    边下边算 SHA-256，下完再校验文件头确实是 jar。
  - `POST /api/update/apply` 先备份当前 jar 为 `<jar>.bak-<版本>` 再替换；
    `POST /api/update/rollback` 从备份恢复。
  - 配置项 `code-association.update.*`：`enabled` / `repo` / `token` /
    `check-interval-minutes` / `cron` / `max-releases` / `include-prerelease`。

### 设计取舍

- **检查自动、替换手动**：定时检查新版本不需要人工干预，但替换 jar 必须点确认。
  服务自己重启自己会让前端拿不到响应，也会把「什么时候中断服务」的决定权从运维手里拿走。
- **不自动重启**：替换后界面提示执行 `./stop.sh && ./start.sh`（Windows 用 `stop.bat` / `start.bat`），
  由外部脚本完成重启，与现有启停脚本的语义一致。
- **三种情况明确拒绝替换**并说明原因：在 IDE 里跑（没有可替换的 jar）、
  Windows（运行中的 jar 被系统锁定）、没先下载（无暂存文件）。
- **GitHub 限流如实暴露**：未认证每小时 60 次，撞上 403 时界面直接说明是限流并给出
  额度重置时间，而不是显示一句含糊的「检查失败」。配置 `GITHUB_TOKEN` 可提到 5000 次/小时。

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
