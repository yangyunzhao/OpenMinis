# OpenAI 设备码登录 Phase 0 验收报告

## 1. 结论

- 状态：被阻塞
- 执行时间：2026-07-30 00:27～00:37（Asia/Shanghai）
- 阻塞范围：Android JVM 测试、Lint 和 Debug APK 基线
- 已通过范围：文档保护、`main` 同步、正式功能分支、remote 安全、上游包含关系、官方协议复核
- 产品代码修改：无
- 是否允许进入 Phase 1：否

Phase 0 已经建立 Git 和协议基线，但本机没有满足项目要求的 Android 工具链，Gradle Wrapper 也未完成首次启动。因此不能取得可信的修改前测试、Lint 和构建结果，Phase 0 不能标为“通过”。

## 2. Git 与分支基线

### 2.1 仓库角色

- 个人远端 `origin`：`https://github.com/yangyunzhao/OpenMinis.git`
- 官方上游 `upstream`：`https://github.com/OpenMinis/OpenMinis.git`
- `upstream` push URL：`DISABLED`
- 默认 push remote：`origin`

### 2.2 实际执行

1. 在临时分支 `wip/openai-device-code-planning` 只提交两份规划文档；
2. 临时文档提交：`f4954e0bc8db122906d3ceb8a86c7c4d86f6a48e`；
3. 切回干净的 `main`；
4. 执行：

   ```powershell
   .\znmlr\Sync-Upstream.ps1 -RetryCount 5 -RetryDelaySeconds 5
   ```

5. 同步脚本实际结果：
   - `origin/main` 已是最新；
   - `upstream/main` 已是最新；
   - 子模块同步完成；
   - `deps/ish/deps/linux` 按 `update = none` 正常跳过；
   - 同步后的 `main` 已推送个人远端；
6. 从同步后的 `main` 创建 `feature/openai-device-code-login`；
7. 将临时文档提交挑拣到正式功能分支；
8. 正式功能分支的规划文档提交：`5c84951aaaf223bc0edb09c88b796a98aded403e`。

### 2.3 提交基线

- 个人 `main` / `origin/main`：`94ab49cbb61203cc95ecd327f34969a2afeb7771`
- 官方 `upstream/main`：`9cf3a855fecd27bb5735b84cacbd56852a3ab8dd`
- 正式功能分支创建基线：`94ab49cbb61203cc95ecd327f34969a2afeb7771`
- `main` 是正式功能分支祖先：是
- `upstream/main` 是正式功能分支祖先：是

临时规划分支暂时保留，只有在正式功能分支提交并推送到个人远端且核对无误后才允许删除。

## 3. 官方 Codex 设备授权协议复核

### 3.1 调查基线

- 复核时间：2026-07-30
- 本机 CLI：`codex-cli 0.145.0`
- 复核期间观察到的 `openai/codex` `main` HEAD：`1da9f846b30f0a6185c0452d39edd4e0fd55fe1c`
- `device_code_auth.rs` 最近修改提交：`d4fcb2873bf23464cfacd804a31d46529db943b0`
- 固定源码：<https://github.com/openai/codex/blob/d4fcb2873bf23464cfacd804a31d46529db943b0/codex-rs/login/src/device_code_auth.rs>
- 官方认证说明：<https://learn.chatgpt.com/docs/auth#login-on-headless-devices>

### 3.2 复核结果

需求文档记录的以下行为仍与官方当前实现一致：

- 请求用户码：`POST {issuer}/api/accounts/deviceauth/usercode`
- 请求字段：`client_id`
- 响应字段：`device_auth_id`、`user_code`、`interval`
- 用户页面：`{issuer}/codex/device`
- 轮询端点：`POST {issuer}/api/accounts/deviceauth/token`
- 轮询字段：`device_auth_id`、`user_code`
- 轮询 HTTP 403/404：继续等待
- 其他非成功轮询状态：失败
- 成功字段：`authorization_code`、`code_challenge`、`code_verifier`
- 当前最长等待：15 分钟
- Token 交换 redirect URI：`{issuer}/deviceauth/callback`
- `codex login --device-auth`：仍存在
- 设备码认证：仍为 beta，需要个人安全设置或 workspace 管理员允许

差异：无实质差异。

实现时需要继续区分：

- 初次请求用户码返回 404：设备码功能未启用；
- 轮询期间返回 403/404：当前仍在等待。

## 4. Android 工具链与资源基线

| 项目 | 项目要求 | 本机实际 | 结果 |
|---|---|---|---|
| JDK | 17 | Windows JDK 25.0.3；WSL JDK 11 | 不符合 |
| Gradle Wrapper | 8.11.1 | 脚本存在，分发包未下载完成 | 被阻塞 |
| Android SDK | compileSdk 36 | 未发现 SDK，环境变量和 `local.properties` 均为空 | 缺失 |
| Android NDK | r28+ | 未发现 | 缺失 |
| CMake | SDK CMake 3.22.1 | Windows 4.3.0；WSL 4.2.3 | 不符合 |
| POSIX shell | 能执行 `gradlew` | Git Bash 5.2.37、WSL2 Ubuntu 可用 | 通过 |
| PRoot 资源 | `assets/proot-aarch64`、`jniLibs/arm64-v8a/libproot.so` | 缺失 | 缺失 |
| Alpine rootfs | `assets/alpine-minirootfs.tar.gz` | 缺失 | 缺失 |
| 顶层子模块 | 与父仓库 gitlink 一致 | `deps/ish`、`deps/proot` 一致 | 通过 |
| 嵌套 Linux 子模块 | `update = none` | 未初始化并被同步脚本正常跳过 | 符合预期 |

其他版本：

- Git：`2.51.1.windows.1`
- GitHub CLI：`2.96.0`
- Android Gradle Plugin：`8.7.3`
- Kotlin：`2.1.0`
- 仓库只有 `src/android/gradlew`，没有 `gradlew.bat`

## 5. 基线验证执行结果

### 5.1 已执行

- `Sync-Upstream.ps1`：通过
- remote 与 push 保护检查：通过
- 分支祖先关系检查：通过
- 两份文档 UTF-8、尾随空白和本地链接检查：通过
- 官方设备授权协议复核：通过

### 5.2 Gradle Wrapper 启动

在 Git Bash 中执行：

```bash
cd /d/repositories/OpenMinis/src/android
./gradlew --version
```

实际结果：

- 首次尝试没有通过系统代理下载 Gradle 分发包；
- 第二次仅为该进程设置 Java/Gradle 代理参数，仍在 64 秒工具时限内无下载进度；
- `.gradle/wrapper` 中只生成了 0 字节临时文件；
- 两次遗留的 Gradle Wrapper Java 进程均已停止；
- 没有修改仓库或全局 Gradle 配置；
- `curl` 对 Gradle 分发地址的只读 HEAD 请求可以经过当前代理到达，但 Java Wrapper 下载仍未成功。

### 5.3 未执行

以下命令没有真正启动：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

原因：

1. Gradle 8.11.1 分发包尚不可用；
2. 缺少项目要求的 JDK 17；
3. Android SDK 36、NDK r28+ 和 SDK CMake 3.22.1 缺失；
4. Debug APK 需要的 PRoot 和 Alpine 构建产物缺失。

这些结果只能标记为“环境阻塞、未执行”，不能标记为测试或构建通过，也不能据此判断当前代码基线是否健康。

## 6. 安全边界

- 未启动 Android 模拟器；
- 未使用真实 OpenAI/ChatGPT 账号；
- 未发起真实模型请求；
- 未修改产品代码；
- 未向 `upstream` 推送；
- 未创建 Pull Request；
- 文档没有记录 Token、授权码、PKCE 或代理凭据。

## 7. Phase 0 门禁判定

| 门禁 | 结果 |
|---|---|
| 文档安全保存 | 通过 |
| 个人 `main` 与官方上游同步 | 通过 |
| 正式功能分支来自最新个人 `main` | 通过 |
| remote 和 push 保护 | 通过 |
| 官方协议与需求一致 | 通过 |
| Android 工具链满足项目要求 | 未通过 |
| 修改前 JVM 测试基线 | 被阻塞，未执行 |
| 修改前 Lint 基线 | 被阻塞，未执行 |
| 修改前 Debug APK 基线 | 被阻塞，未执行 |
| 工作区干净 | 待本报告提交后复核 |

最终状态：**被阻塞**。

## 8. 解除阻塞所需条件

进入 Phase 1 前至少需要：

1. 安装并选择 JDK 17；
2. 安装 Android SDK，并提供 platform 36；
3. 安装 Android NDK r28+；
4. 安装 SDK CMake 3.22.1；
5. 配置 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `src/android/local.properties`；
6. 让 Gradle Wrapper 能通过当前网络代理完成首次下载；
7. 按 `BUILDING.md` 生成：
   - `assets/proot-aarch64`
   - `jniLibs/arm64-v8a/libproot.so`
   - `assets/alpine-minirootfs.tar.gz`
8. 重新执行 JVM 测试、Lint 和 Debug APK 构建并记录真实结果。

安装 JDK、Android SDK/NDK/CMake 和下载构建资源会改变本机环境并产生较大下载量。本报告不把“开始 Phase 0”解释为自动安装这些工具；需要用户明确同意后再进行。
