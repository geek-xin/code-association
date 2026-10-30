---
layout: default
title: code-association 文档
---

# code-association · 文档

跨程序变更影响扫描工具 —— 分析一次代码改动会波及哪些**其他程序**。

## 从这里开始

- [快速上手](wiki/快速上手) — 5 分钟跑通第一次跨程序分析
- [核心概念](wiki/核心概念) — 契约、使用点、传播、风险与置信度
- [架构设计](wiki/架构设计) — 分层与数据流

## 想搞懂判定依据

- [变更识别规则](wiki/变更识别规则)
- [契约匹配详解](wiki/契约匹配详解)
- [数据库影响判断](wiki/数据库影响判断) — 数据库契约的准入条件、兼容性与盲区
- [评分模型](wiki/评分模型)

## 要落地到工程

- [命令行与 CI](wiki/命令行与CI)
- [每日定时扫描](wiki/每日定时扫描)
- [配置导入导出](wiki/配置导入导出) — 一个文件搬走设置项与全部代码源
- [AI 审核](wiki/AI审核) — 可选解释层：配置、隐私边界与失败行为

## 深入与排错

- [故障排查](wiki/故障排查)
- [日志规范](wiki/日志规范) — 日志目录、保留策略、事件目录与排查配方
- [设计取舍](wiki/设计取舍)

## 改界面之前

- [界面规范](wiki/界面规范) — 工作台的唯一权威视觉与交互规范
- [界面实现映射](wiki/界面实现映射) — 规范到文件、类名与数据契约的映射

---

[源码仓库](https://github.com/geek-xin/code-association) ·
[发布版本](https://github.com/geek-xin/code-association/releases) ·
[问题反馈](https://github.com/geek-xin/code-association/issues)