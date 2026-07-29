# OpenAI 设备码登录持续工作记录

## 1. 用途

本文件用于在 Phase 0～Phase 7 的连续开发期间保存进度、验证结果、非阻塞问题、待用户确认事项和恢复上下文。详细需求以
[OPENAI_DEVICE_CODE_LOGIN_REQUIREMENTS.md](OPENAI_DEVICE_CODE_LOGIN_REQUIREMENTS.md)
为准，阶段门禁以
[OPENAI_DEVICE_CODE_LOGIN_PHASE_PLAN.md](OPENAI_DEVICE_CODE_LOGIN_PHASE_PLAN.md)
为准；本记录不得降低两者的安全或验收要求。

## 2. 持续执行边界

- 当前分支：`feature/openai-device-code-login`
- 个人远端：`origin`
- 官方只读上游：`upstream`
- 用户于 2026-07-30 要求尽量连续推进 Phase 0～Phase 7。
- 为解除 Phase 0 环境阻塞，允许安装和配置项目明确要求的本地开发工具链。
- 不启动 Android 模拟器，不远程控制真实设备。
- 不使用真实 OpenAI/ChatGPT 账号，不发起真实模型请求。
- 不把真实 Token、授权码、设备授权 ID、PKCE 或代理凭据写入代码、测试、日志和文档。
- 不向官方 `upstream` 推送，不创建官方仓库 Pull Request。
- Phase 6 必须由用户执行真机验收；在用户反馈前只能准备验收包，不能标记通过。
- Phase 7 依赖用户接受 Phase 6 的真机版本；依赖未满足时只能准备交付材料。

## 3. 当前进度

| 时间 | Phase | 状态 | 说明 |
|---|---|---|---|
| 2026-07-30 00:27～00:37 | Phase 0 | 被阻塞 | Git、上游同步、分支和官方协议复核通过；Android 工具链与基线构建未完成。 |
| 2026-07-30 00:47 | 持续执行 | 进行中 | 用户授权尽量继续推进全部阶段；开始解除 Phase 0 工具链阻塞，并并行调查实现边界。 |
| 2026-07-30 01:51 | Phase 0 | 进行中 | 已准备 WSL JDK 17、SDK 36、NDK r28c、CMake 3.22.1、Gradle 8.11.1、PRoot 和 Alpine；开始运行修改前 JVM 测试。 |
| 2026-07-30 02:23 | Phase 0 | 已通过 | 已建立完整基线：JVM 390 项中 39 项已有失败；Lint 3 个分析任务已有崩溃；Debug APK 构建通过并记录 SHA-256。允许进入 Phase 1。 |
| 2026-07-30 02:24 | Phase 1 | 进行中 | 开始整合协议安全、UI 生命周期和测试架构调查结果，优先处理日志脱敏、OpenAI Token 交换错误收口和删除 Provider 凭据清理。 |
| 2026-07-30 02:39 | Phase 1 | 已通过 | 递归/保守日志脱敏、OpenAI Token 错误收口、callback/redirect 异常日志净化和删除 Provider 凭据清理完成；18 项 focused tests 全绿；完整 405 项测试仍为相同 39 个基线失败。 |
| 2026-07-30 02:40 | Phase 2 | 进行中 | 开始实现只负责单次请求的 OpenAI 设备授权协议核心；持续轮询、重试和总超时留在 Phase 3。 |
| 2026-07-30 03:06 | Phase 2 | 已通过 | 单次用户码、轮询、Token 表单交换、可取消 HTTP 和敏感结果模型完成；16 项 focused tests 全绿；完整 421 项测试仍为相同 39 个基线失败。 |

## 4. 当前阻塞项

Phase 0 的工具链与基线阻塞已解除。当前没有阻止 Phase 1 本地实现的事项。
Phase 6 的真实账号、真机生命周期和真实模型请求仍需用户参与，不能由本地验证替代。

阻塞项的完整证据见
[OPENAI_DEVICE_CODE_LOGIN_PHASE0_REPORT.md](OPENAI_DEVICE_CODE_LOGIN_PHASE0_REPORT.md)。

## 5. 非阻塞问题与待观察项

| 编号 | 首次记录 | 问题 | 当前处理 |
|---|---|---|---|
| NB-01 | 2026-07-30 | 官方 Codex 设备授权端点不是承诺稳定的公开集成 API。 | 协议常量集中管理，测试固定当前语义；Phase 5 和交付前再次复核。 |
| NB-02 | 2026-07-30 | 当前仓库只有 POSIX `gradlew`，没有 `gradlew.bat`。 | Windows 上通过 Git Bash 执行，不新增无关 Wrapper 文件。 |
| NB-03 | 2026-07-30 | Phase 6 的后台、锁屏、旋转、Activity 重建和真实账号流程不能由本地 JVM 测试完全替代。 | Phase 3 只做逻辑级验证；Phase 6 提供明确的用户真机检查表。 |
| NB-04 | 2026-07-30 | Phase 7 依赖用户接受 Phase 6，无法在用户离线期间正式通过。 | 预先整理交付材料；等待用户真机反馈后收口。 |
| NB-05 | 2026-07-30 | Windows checkout 把 POSIX 脚本写成 CRLF，WSL 直接执行会出现 `set: -\r: invalid option`。 | 运行前只对目标脚本机械转换为 LF，运行后恢复 CRLF；不把行尾变化提交到功能分支。 |
| NB-06 | 2026-07-30 | `BUILDING.md` 要求资源脚本使用 NDK r28+，但 AGP 8.7.3 的 Android 构建仍自动准备其默认 NDK 27.0.12077973。 | PRoot 明确使用 r28c 构建；Gradle 使用其解析到的 NDK。记录两个用途和版本，不把自动安装误认为需求变更。 |
| NB-07 | 2026-07-30 | Gradle 8.11.1 官方包经 GitHub/当前代理下载很慢。 | 改用同一官方分发包的可续传下载，核对官方 SHA-256 后预置 Wrapper 缓存；不修改仓库中的 Wrapper URL。 |
| NB-08 | 2026-07-30 | Debug 构建声明 `.claude/skills/debug-server` 为可选输入，但 Gradle 在脚本生成占位文档前仍要求目录存在；该目录被 Git 忽略。 | 本机创建空的忽略目录以建立基线；不改产品代码。若后续希望改善首次构建体验，可另行向上游报告或修正任务输入声明。 |
| NB-09 | 2026-07-30 | AGP 8.7.3 只验证至 compileSdk 35，而项目使用 36；CMake 还报告 SDK XML 版本 4/3 兼容警告。 | 当前 Debug APK 构建成功，先作为非阻塞工具链警告记录；本功能不顺带升级 AGP/CMake，Phase 5 再核对警告是否恶化。 |
| NB-10 | 2026-07-30 | 现有浏览器 OAuth 存在与设备码相关的敏感日志：callback request、Redirect URI 和 OpenAI state 可能把 code/state 写入 logcat。 | 纳入 Phase 1 安全加固并补充纯 JVM 可验证的日志净化测试；不扩大到无关 Provider 协议重构。 |
| NB-11 | 2026-07-30 | 现有 OpenAI 浏览器 OAuth 在 state 不匹配时只告警并继续交换授权码。 | 这是既有浏览器 OAuth 安全问题，不是设备码协议所需变更；先记录，避免夜间无确认扩大行为变化。后续单独讨论是否按“拒绝不匹配回调”修复。 |
| NB-12 | 2026-07-30 | 现有浏览器 OAuth 若在用户删除 Provider 时仍有登录任务运行，迟到的成功结果理论上可能重新写入该旧 instance ID 的孤立凭据。 | 普通“删除旧 Provider 后重新新建”不会主动制造此竞态；Phase 1 不扩大既有浏览器流程。Phase 3/4 的新设备码协调器必须在写凭据前校验 attempt 仍有效，并只在 Provider 严格保存成功后提交长期凭据。 |
| NB-13 | 2026-07-30 | 通用敏感字段名 `key` 会让日志脱敏器遮蔽某些无害的同名诊断字段。 | 安全优先，接受少量日志可读性损失；不影响真实请求或持久化数据。 |
| NB-14 | 2026-07-30 | OpenAI Token 响应允许省略 `expires_in`；此时兼容存储不会生成 `expire_at`，现有 `validAccessToken()` 也不会主动按时间刷新。 | 不臆造服务端过期时间；Phase 5 用带 `expires_in` 的模拟过期 Token 验证刷新路径，真实刷新留 Phase 6。 |
| NB-15 | 2026-07-30 | 一次被外部超时中止的 Gradle 试跑留下损坏的 KSP `symbolLookups` 增量缓存。 | 已把缓存隔离为 `app/build/kspCaches/debug.corrupt-phase2` 并成功重建；目录被 Git 忽略，Phase 5 完整构建清理时移除。 |

## 6. 待用户回来后确认

目前没有必须立即阻断本地开发的问题。后续需要用户参与的事项集中留到 Phase 6：

1. 选择真实 Android 设备、系统版本和网络环境；
2. 决定是否使用真实 ChatGPT/OpenAI 账号完成授权；
3. 决定是否执行可能产生账户用量的最小真实模型请求；
4. 按真机检查表反馈生命周期、删除后新建和应用重启结果。

## 7. 恢复工作检查

每次恢复工作时先执行：

1. `git status --short --branch`
2. 核对当前 Phase 的验收门禁；
3. 查看本文件的最新进度、阻塞项和非阻塞问题；
4. 只在当前 Phase 通过后进入下一 Phase；
5. 每个 Phase 使用独立、详细的中文提交，并推送到个人远端。
