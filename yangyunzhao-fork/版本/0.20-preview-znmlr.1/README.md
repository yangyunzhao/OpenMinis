# `0.20-preview-znmlr.1` 版本资料

本目录保存以个人预览版 `0.20-preview-znmlr.1` 为事实基线形成的发布资料。Release、tag 和
构建物以
[GitHub Release](https://github.com/yangyunzhao/OpenMinis/releases/tag/0.20-preview-znmlr.1)
为准。

> [!IMPORTANT]
> **版本基线更正（2026-08-06）：** 本个人版本于 2026-07-30 发布，当时官方尚未
> 发布 `0.22-preview`，因此沿用了源码中的 `0.20-preview` 标识。它采用的官方
> 上游基线实际是提交
> [`9cf3a855fecd27bb5735b84cacbd56852a3ab8dd`](https://github.com/OpenMinis/OpenMinis/commit/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd)；
> 官方随后于 2026-08-01 把同一提交发布为
> [`0.22-preview`](https://github.com/OpenMinis/OpenMinis/releases/tag/0.22-preview)。
> 因此，本版本应理解为“官方 `0.22-preview` 源码基线 + 个人修改”。已经公开的
> tag、APK 和应用内版本号仍保持 `0.20-preview-znmlr.1` / `20001`；此更正不表示
> 该 APK 是官方构建，也不表示它与官方 APK 内容相同。

## 版本文档

- [个人预览版已知限制解释与解决计划](个人预览版已知限制解释与解决计划.md)

设备码登录的需求、实施过程和 Phase 0～Phase 7 验收证据属于功能档案，见
[OpenAI 设备码登录](../../功能/OpenAI设备码登录/README.md)。

随当前个人 `main` 持续更新的文件级差异不属于版本快照，见
[个人派生仓库相对官方上游修改清单](../../仓库维护/个人派生仓库相对官方上游修改清单.md)。

以后发布个人版本时，按
[发布基线核对说明](../../仓库维护/发布基线核对说明.md) 从官方 Release tag 解析
精确提交，并核对 APK 自身的版本信息。

版本目录使用完整 Git tag 命名。发布后的新功能计划或下一版本变更不追加到这里；
若需要修正文档中的事实，应明确说明修订内容，不能改写既有构建与验收结果。
