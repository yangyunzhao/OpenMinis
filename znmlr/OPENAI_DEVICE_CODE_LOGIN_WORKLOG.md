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

## 4. 当前阻塞项

1. 缺少项目要求的 JDK 17。
2. 缺少 Android SDK platform 36、NDK r28+ 和 SDK CMake 3.22.1。
3. Gradle Wrapper 尚未完成首次下载。
4. Debug APK 所需的 PRoot 与 Alpine 资源尚未生成。

阻塞项的完整证据见
[OPENAI_DEVICE_CODE_LOGIN_PHASE0_REPORT.md](OPENAI_DEVICE_CODE_LOGIN_PHASE0_REPORT.md)。

## 5. 非阻塞问题与待观察项

| 编号 | 首次记录 | 问题 | 当前处理 |
|---|---|---|---|
| NB-01 | 2026-07-30 | 官方 Codex 设备授权端点不是承诺稳定的公开集成 API。 | 协议常量集中管理，测试固定当前语义；Phase 5 和交付前再次复核。 |
| NB-02 | 2026-07-30 | 当前仓库只有 POSIX `gradlew`，没有 `gradlew.bat`。 | Windows 上通过 Git Bash 执行，不新增无关 Wrapper 文件。 |
| NB-03 | 2026-07-30 | Phase 6 的后台、锁屏、旋转、Activity 重建和真实账号流程不能由本地 JVM 测试完全替代。 | Phase 3 只做逻辑级验证；Phase 6 提供明确的用户真机检查表。 |
| NB-04 | 2026-07-30 | Phase 7 依赖用户接受 Phase 6，无法在用户离线期间正式通过。 | 预先整理交付材料；等待用户真机反馈后收口。 |

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
