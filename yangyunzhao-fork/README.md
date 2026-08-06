# OpenMinis 个人维护文档

本目录保存 `yangyunzhao/OpenMinis` 个人 Fork 的维护、功能和版本资料。当前个人版本为
[`0.20-preview-znmlr.1`](版本/0.20-preview-znmlr.1/README.md)。

目录名使用 GitHub 所有者 `yangyunzhao` 加 `-fork` 后缀，明确表示这些内容只属于
个人派生仓库，并降低与官方上游新增通用目录发生重名的可能。

## 文档分类

| 分类 | 适用范围 | 入口 |
|---|---|---|
| 仓库维护 | 跨功能、跨版本长期生效的同步规则和当前 Fork 修改清单 | [仓库维护文档](仓库维护/个人派生仓库与官方上游同步说明.md) |
| 功能 | 某项功能从需求、设计、实施到验收的完整历史 | [OpenAI 设备码登录](功能/OpenAI设备码登录/README.md) |
| 版本 | 与某个 Git tag、发布构建或当时限制直接绑定的快照 | [`0.20-preview-znmlr.1`](版本/0.20-preview-znmlr.1/README.md) |

Windows 上游同步工具位于
[`仓库维护/Sync-Upstream.ps1`](仓库维护/Sync-Upstream.ps1)。从仓库根目录运行：

```powershell
.\yangyunzhao-fork\仓库维护\Sync-Upstream.ps1
```

准备个人 Release 前，先阅读
[发布基线核对说明](仓库维护/发布基线核对说明.md)，并运行
[`Verify-UpstreamRelease.ps1`](仓库维护/Verify-UpstreamRelease.ps1) 核实官方
Release、tag、精确提交和源码版本信息。

当前个人 `main` 相对官方上游的动态文件清单见
[个人派生仓库相对官方上游修改清单](仓库维护/个人派生仓库相对官方上游修改清单.md)。

## 归档规则

1. 跨版本规则放入 `仓库维护/`，并持续维护同一份权威文档。
2. 功能需求、实施计划、工作记录和阶段验收放入 `功能/<功能名>/`。
3. 发布专属资料放入 `版本/<完整 Git tag>/`，不使用 `latest`、`当前版本` 等会漂移的目录名。
4. 一个阶段只有一份主要报告时，不再为该阶段创建单独目录。
5. 当前没有图片或附件，因此不预建空目录；以后应把材料放在最接近其作用域的
   `assets/` 中。
6. APK 和大型日志保存在 GitHub Release 或其他合适的制品存储中，文档只记录链接、
   对应提交和 SHA-256。Token、授权码、PKCE、真实账号信息及未脱敏日志不得入库。
