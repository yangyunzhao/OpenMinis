# OpenAI 设备码登录 Phase 7 交付材料预备稿

## 1. 文档状态

- 文档性质：Phase 7 交付材料预备稿
- 编制日期：2026-07-30
- 当前功能分支：`feature/openai-device-code-login`
- 当前结论：Phase 0～Phase 5 已形成可供真机验收的代码与 Debug APK；正式验收仍在等待用户完成 Phase 6
- Phase 6 状态：待用户在真实 Android 设备上执行
- Phase 7 状态：**未通过**

> 本文件只提前整理交付关系、安全边界和后续操作，不能替代 Phase 6
> 的真机证据。只有用户完成并接受 Phase 6，所有缺陷均按责任 Phase
> 回流、修复并重新验证后，才能正式验收 Phase 7。

## 2. 本次预交付范围

本次预交付面向个人 Fork 中的 Android OpenAI 设备码登录功能，主要包括：

1. OpenAI OAuth 日志与凭据清理安全基础；
2. 设备授权协议模型、HTTP 请求和轮询结果分类；
3. 登录状态协调、取消、超时、重试和迟到结果保护；
4. 新建 OpenAI Provider 时的浏览器登录与设备码登录双入口；
5. 严格的 Provider、凭据和模型跨存储提交、补偿及启动恢复；
6. 设备码 Token 与既有 OpenAI OAuth 自动刷新路径的兼容回归；
7. 可由用户安装并执行 Phase 6 的 Debug APK；
8. 个人 Fork、官方只读上游、对应源码和后续同步维护说明。

以下内容明确不属于“已经完成验收”的范围：

- 真实 Android 设备上的安装、登录、旋转、后台、锁屏、进程重建和删除后新建；
- 真实 ChatGPT/OpenAI 账号的授权结果；
- 真实 Token 刷新和真实模型请求；
- Release 签名、应用商店分发或生产发布；
- 向官方仓库创建 Pull Request；
- Phase 7 的正式通过。

## 3. Phase 0～Phase 5 提交映射

下表中的短 SHA 是各 Phase 的主要代码或验收提交入口。需要审计时应使用
`git show <SHA>` 查看完整提交，并结合对应报告核对测试边界。

| Phase | 主要提交 | 主要内容 | 当前交付含义 |
|---|---|---|---|
| Phase 0 | `9f64e60` | 基线环境、分支、协议复核与已有失败记录 | 建立后续阶段的可比较基线 |
| Phase 1 | `3ba1e1e` | 凭据清理、敏感日志收口与相关安全回归 | 建立设备码接入前的安全边界 |
| Phase 2 | `b42f3fb` | 设备授权协议模型、单次 HTTP 请求和响应分类 | 提供可独立测试的协议核心 |
| Phase 3 | `b27fc9e` | 登录状态机、协调器、取消、超时、重试和竞态保护 | 提供路由级生命周期与并发基础 |
| Phase 4 | `60fa227` | UI 接入、Provider 持久化、补偿、恢复和模型集成 | 完成面向用户的功能闭环 |
| Phase 5 | `a000037` | 设备码 Token 自动刷新兼容与回归测试 | 形成此次 Debug APK 的代码状态 |

以上提交均位于 `feature/openai-device-code-login` 的连续历史中。正式交付前仍须
重新执行以下检查，防止后续提交、上游同步或缺陷修复使映射失效：

```powershell
git log --oneline --decorate -10
git merge-base --is-ancestor 9f64e60 HEAD
git merge-base --is-ancestor a000037 HEAD
```

## 4. 构建提交 A、文档提交 B 与远端 HEAD

必须区分以下三个身份，不能把“最新文档提交”误写成 APK 的构建源码：

### 4.1 构建提交 A

- 短 SHA：`a000037`
- 完整 SHA：`a0000370cf15027c40437ad06140a03260465827`
- 提交说明：`认证：完成设备码令牌 Phase 5 自动刷新回归`
- 作用：本预备稿所列 Debug APK 的实际构建源码

APK 应始终对应构建提交 A。后续只修改 `znmlr/` 文档的提交，不会改变已经生成的
APK 字节，也不能被写成 APK 的“对应 Git 提交”。

### 4.2 文档提交 B

- SHA：`待本文件提交后补录`
- 预计内容：Phase 5 报告、Phase 6 清单身份、工作记录、阶段计划与本交付预备稿
- 作用：保存验证证据和交接材料
- 限制：文档提交 B 不是 APK 的构建提交

由于 Git 提交 SHA 只能在提交完成后得到，本文件创建时不能预先写入或编造
文档提交 B 的 SHA。最终状态应从 Git 读取：

```powershell
git log -1 --format='%H %s'
```

### 4.3 个人远端功能分支 HEAD

- 本文件编制时的已确认远端 HEAD：`a0000370cf15027c40437ad06140a03260465827`
- 本文件提交并推送后：应更新为文档提交 B 或其后的已审核提交
- 正式 Phase 7 验收时：必须重新读取，不能沿用本预备稿中的历史快照

核对命令：

```powershell
git fetch origin
git rev-parse HEAD
git rev-parse origin/feature/openai-device-code-login
git status --short --branch
```

若本地 HEAD 与个人远端功能分支 HEAD 不一致，应先确认差异原因；不得仅为“看起来
一致”而使用破坏性 reset、普通 `--force` 或丢弃未提交工作。

## 5. Debug APK 身份

| 项目 | 值 |
|---|---|
| 文件名 | `app-debug.apk` |
| 仓库相对路径 | `src/android/app/build/outputs/apk/debug/app-debug.apk` |
| 本机绝对路径 | `D:\repositories\OpenMinis\src\android\app\build\outputs\apk\debug\app-debug.apk` |
| 文件大小 | `40,716,639` 字节 |
| SHA-256 | `BA70EE63B3E7D2E3F3F992053A6A908010621464370817DE8F4081980CEEC931` |
| 对应构建提交 | `a0000370cf15027c40437ad06140a03260465827` |
| 构建类型 | `Debug` |
| 用途 | 仅供 Phase 6 用户真机验收 |

用户安装前应在本机重新计算哈希：

```powershell
Get-FileHash `
  -Algorithm SHA256 `
  -LiteralPath 'D:\repositories\OpenMinis\src\android\app\build\outputs\apk\debug\app-debug.apk'
```

若文件大小或 SHA-256 与本表不一致，应停止安装。此时应确认是否重新构建过 APK，
并以新的构建提交、文件大小和哈希更新 Phase 6 清单及本文件，不能继续沿用旧身份。

## 6. 报告与操作入口

### 6.1 需求、计划和持续记录

- [需求与已确认决策](OPENAI_DEVICE_CODE_LOGIN_REQUIREMENTS.md)
- [Phase 0～Phase 7 实施计划](OPENAI_DEVICE_CODE_LOGIN_PHASE_PLAN.md)
- [持续工作、非阻塞问题与恢复上下文](OPENAI_DEVICE_CODE_LOGIN_WORKLOG.md)

### 6.2 分阶段报告

- [Phase 0 基线报告](OPENAI_DEVICE_CODE_LOGIN_PHASE0_REPORT.md)
- [Phase 1 安全基础报告](OPENAI_DEVICE_CODE_LOGIN_PHASE1_REPORT.md)
- [Phase 2 协议核心报告](OPENAI_DEVICE_CODE_LOGIN_PHASE2_REPORT.md)
- [Phase 3 状态协调报告](OPENAI_DEVICE_CODE_LOGIN_PHASE3_REPORT.md)
- [Phase 4 UI 与持久化集成报告](OPENAI_DEVICE_CODE_LOGIN_PHASE4_REPORT.md)
- [Phase 5 自动化回归与 Debug 构建报告](OPENAI_DEVICE_CODE_LOGIN_PHASE5_REPORT.md)

### 6.3 下一步与维护入口

- [Phase 6 真机验收清单](OPENAI_DEVICE_CODE_LOGIN_PHASE6_DEVICE_CHECKLIST.md)
- [个人 Fork 与官方上游同步约定](UPSTREAM_SYNC.md)
- [Windows 上游同步脚本](Sync-Upstream.ps1)

若 Phase 5 报告尚未随文档提交 B 落库，应将其视为交付材料未完整，不得仅凭本文件
宣布 Phase 5 或 Phase 7 通过。

## 7. Git 远端与安全边界

### 7.1 远端角色

| 名称 | 地址或配置 | 用途 |
|---|---|---|
| `origin` | `https://github.com/yangyunzhao/OpenMinis.git` | 保存个人分支、个人修改和交付历史 |
| `upstream` fetch | `https://github.com/OpenMinis/OpenMinis.git` | 获取官方公开镜像更新 |
| `upstream` push | `DISABLED` | 防止误向官方上游推送 |

安全边界：

1. 功能分支只推送到个人远端 `origin`；
2. `upstream` 只读，不向其推送；
3. 不把功能分支直接混入个人 `main`，除非用户另行决定并完成相应验证；
4. 不使用 `git push --force`；确需在个人独占分支 rebase 后更新时也只能审慎使用
   `--force-with-lease`；
5. 上游冲突必须理解双方修改意图并运行相关测试，不能自动选择 `ours` 或
   `theirs`；
6. 同步前检查工作区，避免把用户或其他任务的未提交修改混入同步过程。

### 7.2 上游同步入口

完整说明以 [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) 为准。日常流程为：

```powershell
git switch main
.\znmlr\Sync-Upstream.ps1
git switch feature/openai-device-code-login
git rebase main
```

若功能分支不适合改写历史，可使用 `git merge main`。发生冲突时脚本会保留现场，
应先检查：

```powershell
git status
git diff --name-only --diff-filter=U
```

合并或 rebase 完成后，需要重新执行与冲突文件相关的 focused tests、完整 JVM
回归、静态检查和 Debug 构建；如果产生新 APK，还必须更新 APK 身份和 Phase 6
清单。

## 8. 官方 PR 状态

- 尚未向 `OpenMinis/OpenMinis` 创建 Pull Request。
- 本阶段也不计划自动创建官方 Pull Request。
- 官方仓库在现有项目说明中属于不接受 PR 的公开镜像；功能建议应按其说明通过
  Issue 等渠道提出。
- 用户最初设想的“由原作者决定是否合并”不能被当作已经发生；若官方政策未来变化，
  仍须由用户确认是否公开提交代码、说明和测试证据。

任何未来的公开 Issue、PR 或发布说明都不得包含真实授权码、Token、设备授权 ID、
PKCE、账号标识、私有代理信息或未经脱敏的日志。

## 9. GPLv3 与对应源码

OpenMinis 使用 GPLv3。个人可以 fork、修改和自行构建；若把修改后的 APK
提供给他人，必须同时履行 GPLv3 下的义务，尤其需要注意：

1. 为所分发的二进制提供其对应版本的完整对应源码；
2. 保留版权和许可证声明；
3. 修改后的派生作品继续按 GPLv3 提供；
4. 确保接收者能获得用于生成该二进制的实际源码，而不是仅获得更新后的文档 HEAD。

因此，本 APK 的对应源码锚点是构建提交 A
`a0000370cf15027c40437ad06140a03260465827`。如果文档提交 B 成为远端 HEAD，
对外说明时仍必须明确 APK 是由构建提交 A 生成；若后来修复代码并重新构建，则应建立
新的构建提交、APK 哈希和对应源码关系。

本预备稿不是法律意见。计划对外分发时，应重新核对仓库中的实际许可证文本、依赖
许可证和分发方式。

## 10. Phase 6 尚未覆盖的真机风险

### 10.1 安装与签名

- 当前产物是 Debug APK，不是 Release 包。
- Debug APK 使用本机构建环境的调试签名；若设备上已有相同包名但签名不同的应用，
  可能无法覆盖安装。
- 不应为解决签名冲突而直接卸载日常使用的应用，因为卸载可能清除 Provider、会话和
  其他本地数据。
- 遇到安装失败时先保存错误信息并停止，由用户决定是否使用备用设备、备份数据或采用
  其他隔离方案。

### 10.2 真实协议

- OpenAI/Codex 设备授权端点和响应行为不是本项目可以保证长期稳定的公开集成契约。
- MockWebServer 和 JVM 测试只能验证当前实现对已知响应的处理，不能证明真实服务端
  没有改变端点、字段、轮询状态、限流或错误语义。
- 真实页面的域名、证书、提示内容或请求看起来异常时应立即停止，不输入授权码。
- 一次性授权码同样属于敏感信息，不应出现在聊天、截图、Issue、报告或公开日志中。

### 10.3 真实请求与账号影响

- 真实登录会使用用户有权使用的 ChatGPT/OpenAI 账号，并可能改变该账号的授权状态。
- 真实模型请求可能消耗额度、配额或产生费用。
- 是否执行最小真实模型请求，必须由用户在 Phase 6 明确选择；默认不执行。
- Token 自动刷新不要求通过篡改系统时间、加密存储或账号状态强行触发。
- 不使用日常关键 Provider 做破坏性验收；“已有 Provider 重新登录”的产品决策是
  删除旧 Provider 后重新新建。

### 10.4 Android 生命周期与本地存储

以下行为必须由真机验证，不能用当前 JVM 测试或 Debug 构建成功替代：

- Activity 被系统回收后重建；
- 旋转、切后台、锁屏、返回应用和取消授权；
- 浏览器或 Custom Tab 中断后回到应用；
- 进程强杀发生在跨存储提交的不同阶段；
- Android Keystore、EncryptedSharedPreferences 和 Room 的真实组合行为；
- 删除新建 Provider 后，凭据、模型和恢复标记是否按预期清理；
- 厂商后台限制或深度睡眠对 15 分钟超时和轮询的影响。

## 11. Phase 6 执行与证据要求

用户应使用
[Phase 6 真机验收清单](OPENAI_DEVICE_CODE_LOGIN_PHASE6_DEVICE_CHECKLIST.md)
逐项执行。至少应满足：

1. 安装前核对 APK 路径、大小、SHA-256 和构建提交；
2. 使用由用户控制且有权使用的设备、网络和账号；
3. 不记录任何真实敏感值；
4. 对每项填写“通过、失败、经确认跳过”之一，不能留空后宣布完成；
5. 失败时记录可脱敏的现象、时间、设备/系统版本、操作顺序和是否可重现；
6. 真实模型请求保持用户可选，跳过不等于其他真机功能自动通过；
7. 所有必须项完成且用户明确接受后，才允许讨论 Phase 7 正式验收。

用户反馈应优先提供现象和可重现步骤。日志如包含真实敏感字段，应先停止分享并进行
脱敏；不能为了方便诊断而把 Token 或授权码交给 Codex。

## 12. 缺陷回流规则

Phase 6 发现问题后，不在本文件中直接把 Phase 7 标记为通过，也不把所有问题都归为
“真机差异”。应按根因回到责任 Phase：

| 缺陷类型 | 主要回流 Phase | 修复后的最低复验 |
|---|---|---|
| 日志泄露、删除后凭据残留 | Phase 1 | 安全 focused tests、完整 JVM 回归、真机相关项 |
| 端点、字段、轮询状态或 Token 交换变化 | Phase 2 | 协议测试、错误分类、完整 JVM 回归、真机登录 |
| 超时、取消、重试、迟到结果或并发竞态 | Phase 3 | 协程/并发测试、生命周期真机项 |
| UI、复制、Custom Tab、Provider 提交、补偿或恢复 | Phase 4 | UI/持久化 focused tests、Debug 构建、真机对应项 |
| 自动刷新、兼容回归或构建产物 | Phase 5 | 刷新测试、完整 JVM 回归、Lint 基线比较、重新构建 |

缺陷闭环步骤：

1. 在工作记录中新增编号、现象、影响和是否阻塞；
2. 保存脱敏后的最小复现信息；
3. 在责任 Phase 做范围最小且完整的修复；
4. 先执行 focused tests，再执行后续阶段门禁；
5. 代码变化后创建新的详细中文提交；
6. 重新生成 APK 并更新大小、SHA-256 和构建提交；
7. 只重测受影响项不足以证明整体无回归时，补做完整 Phase 6；
8. 用户明确接受新的真机结果后，再更新正式 Phase 7 结论。

如问题不阻塞当前验收但需要后续观察，应记录到
[持续工作记录](OPENAI_DEVICE_CODE_LOGIN_WORKLOG.md)，不能依赖聊天上下文保存。

## 13. Phase 7 正式验收门禁

以下项目全部满足前，Phase 7 必须保持“未通过”：

- [ ] 用户已完成并接受 Phase 6 真机验收。
- [ ] Phase 6 发现的阻塞缺陷已回流修复并复验。
- [ ] 最终构建提交、APK 大小和 SHA-256 已重新核对。
- [ ] 文档提交 B 或后续最终文档提交已推送到个人远端。
- [ ] 本地 HEAD 与 `origin/feature/openai-device-code-login` 的预期状态一致。
- [ ] 工作区无意外修改，所有有意保留的未跟踪文件均有明确说明。
- [ ] `origin` 和只读 `upstream` 的角色与 push 防护仍正确。
- [ ] 未向官方仓库误推送，也未创建未经用户确认的官方 PR。
- [ ] 自动化测试、已有基线失败、未执行验证和真机结果均如实记录。
- [ ] 若对外分发 APK，已准备 GPLv3 所要求的对应源码与许可证信息。
- [ ] 上游更新后的冲突处理和必要回归已完成，或明确记录本次交付基于哪个上游基线。

正式通过时，应在本文件或独立的最终 Phase 7 报告中补充：

1. Phase 6 用户接受日期与不含敏感信息的结论；
2. 最终构建提交；
3. 最终文档提交；
4. 个人远端功能分支 HEAD；
5. APK 最终大小和 SHA-256；
6. 自动化与真机测试摘要；
7. 已知风险、跳过项和用户决定；
8. 是否仅保留功能分支、是否合并个人 `main`、是否公开分发。

在这些证据补齐前，本预备稿的最终结论始终是：

> **Phase 7 交付材料已预备，但 Phase 7 未通过；等待用户完成并接受 Phase 6。**
