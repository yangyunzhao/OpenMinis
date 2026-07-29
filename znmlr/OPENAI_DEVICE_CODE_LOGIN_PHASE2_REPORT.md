# OpenAI 设备码登录 Phase 2 验收报告

## 1. 结论

- 状态：通过
- 执行时间：2026-07-30 02:40～03:06（Asia/Shanghai）
- 基线提交：`3ba1e1e9ee921bb380301d42b1dbba18c8afae1f`
- 阶段提交：本报告所在的 Phase 2 独立提交
- 是否允许进入 Phase 3：是

Phase 2 已完成不依赖 Compose、Activity、真实账号或真实 OpenAI 网络的设备授权协议核心。
16 项 focused tests 全部通过；完整 JVM 测试从 Phase 1 的 405 项增加到 421 项，失败
仍为相同 3 个测试套件中的 39 项，没有新增或恶化。

## 2. 协议实现

### 2.1 集中的生产配置

`OpenAIDeviceAuthDefaults` 集中保存当前 Codex 设备授权所需的：

- issuer：`https://auth.openai.com`
- 既有 OAuth client ID
- 用户码端点：`/api/accounts/deviceauth/usercode`
- 单次轮询端点：`/api/accounts/deviceauth/token`
- 用户登录页面：`/codex/device`
- Token 交换端点：`/oauth/token`
- Token 交换 redirect URI：`/deviceauth/callback`
- 当前最长轮询时间：15 分钟，供 Phase 3 协调器使用

现有 `OpenAIOAuthManager` 的浏览器登录也改为引用同一 issuer、Token URL 和 client ID，
避免两条 OpenAI 登录路径今后出现常量漂移。测试可注入完整端点集合并强制指向
MockWebServer。

### 2.2 单次用户码请求

实现发送 JSON：

```json
{"client_id":"..."}
```

成功响应严格要求：

- `device_auth_id`：非空白字符串；
- `user_code`：非空白字符串，兼容官方 `usercode` 别名；
- `interval`：字符串形式的正十进制整数，拒绝缺失、零、负数、小数、数字 JSON
  类型和 `Long` 溢出；
- `user_code` 与 `device_auth_id` 作为不透明值原样保留，不 trim、不改写格式。

初始请求的 HTTP 404 单独分类为 `Unsupported`；其他非 2xx 只保留状态码。

### 2.3 单次轮询

单次轮询发送 JSON：

```json
{"device_auth_id":"...","user_code":"..."}
```

分类严格跟随当前官方实现：

- HTTP 403、404：`Pending`，不解析正文；
- 其他非 2xx：终止性 HTTP 错误；
- 2xx：严格要求非空白字符串
  `authorization_code`、`code_challenge` 和 `code_verifier`。

本阶段没有自行发明 `slow_down`、用户拒绝或授权过期等服务端未提供的语义，也没有
提前实现持续轮询、延时、重试和总超时。

### 2.4 Token 交换与现有存储兼容

Token 交换使用 `application/x-www-form-urlencoded`，精确发送：

- `grant_type=authorization_code`
- `code`
- `redirect_uri`
- `client_id`
- `code_verifier`

不发送 `code_challenge`、`client_secret` 或完整 JSON 请求体。成功响应只白名单保留
现有刷新路径需要的：

- `access_token`
- `refresh_token`
- `id_token`
- 可选正整数 `expires_in`

不保留服务端完整原始响应。`expires_in` 只接受精确整数 JSON 类型；拒绝浮点、
科学计数法、字符串和超范围 `BigInteger`，防止截断或回绕。转换为现有
`OAuthManager` 存储 JSON 时生成 `expire_at`，加法溢出会饱和到 `Long.MAX_VALUE`。
本阶段只返回内存对象，不写长期凭据或 Provider。

## 3. 取消与敏感信息边界

三个 HTTP 操作都通过 `Call.enqueue` 和 `suspendCancellableCoroutine` 执行：

- 协程取消会直接调用底层 `Call.cancel()`；
- 取消与 HTTP 响应并发时，迟到的 `Response` 会被关闭；
- `CancellationException` 不会被包装为网络错误；
- 网络失败只返回无正文、无异常 message 的 `Network` 分类；
- HTTP 错误只保留状态码；
- 所有含设备 ID、用户码、授权码、PKCE 或 Token 的对象都覆写安全 `toString()`。

Phase 3 仍须增加整个登录尝试的 generation/attempt 身份保护；单次请求可取消不能替代
跨请求的迟到结果防护。

## 4. 修改文件

产品代码：

- `src/android/app/src/main/java/com/openminis/app/auth/OpenAIDeviceAuthClient.kt`
- `src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt`

测试：

- `src/android/app/src/test/java/com/openminis/app/auth/OpenAIDeviceAuthClientTest.kt`

文档：

- `znmlr/OPENAI_DEVICE_CODE_LOGIN_PHASE2_REPORT.md`
- `znmlr/OPENAI_DEVICE_CODE_LOGIN_PHASE_PLAN.md`
- `znmlr/OPENAI_DEVICE_CODE_LOGIN_WORKLOG.md`

## 5. 验证证据

### 5.1 Focused JVM tests

执行：

```bash
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest \
  --tests 'com.openminis.app.auth.OpenAIDeviceAuthClientTest'
```

结果：16 tests，0 failure，0 error，0 skipped；Gradle `BUILD SUCCESSFUL in 47s`。

覆盖：

- 生产端点和 15 分钟常量；
- 三种 HTTP 请求的方法、路径、Content-Type 和精确字段；
- `usercode` 别名、原样 opaque 值和 interval 严格解析；
- 404 unsupported、403/404 pending 和其他终止状态；
- 所有必需字段的缺失、错误类型、空白值及具体协议错误分类；
- Token 白名单、Form URL Encoding、可选 expiry 和溢出保护；
- 传输失败、MockWebServer 无响应取消和直接 `Call.cancel()` 证据；
- 所有公开结果 `toString()` 的敏感字段遮蔽；
- 全部网络只指向本地 MockWebServer。

### 5.2 完整 JVM 回归

执行：

```bash
./gradlew --no-daemon --console=plain :app:testDebugUnitTest
```

结果：421 tests completed，39 failed，约 32 秒。与 Phase 0、Phase 1 完全一致：

- `AnthropicProviderTest`：24 项失败，仍为缺少
  `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`；
- `OpenAIProviderTest`：11 项失败，仍为 MockWebServer 路径的
  `Server returned an empty response`；
- `TerminalSanitizerTest`：4 项失败，仍为 CR folding 比较差异。

新增的 16 项全部通过，没有新增失败套件、失败数量或错误签名。

### 5.3 其他检查

- 修改后的 main/debug Kotlin 和 unit test Kotlin 均编译通过；
- `git diff --check`：通过；
- 静态日志搜索：协议实现没有日志调用、异常正文或 Throwable 输出；
- 独立审查第一轮发现并已修复：
  - `expire_at` 加法溢出；
  - `expires_in` 浮点和超大整数误接受；
  - 测试只断言笼统 Failure、未断言具体原因；
  - 取消测试未直接证明 `Call.cancel()`。
- 第二轮审查发现并已修复 `BigInteger.longValueExact()` 只在 Android API 31+
  可用的问题；最终实现改用 API 26 可用的符号和范围比较后再转换。

## 6. 构建过程中的非产品问题

第一次短时试跑被外部命令超时终止后，WSL 挂载盘上的 KSP
`symbolLookups` 增量缓存无法 flush。该缓存已可恢复地隔离到：

```text
src/android/app/build/kspCaches/debug.corrupt-phase2
```

重新生成 KSP 缓存后主源码成功编译。该目录位于被忽略的 build 输出内，不会提交，
也不影响后续增量构建。Phase 5 执行完整构建清理时可一并移除。

## 7. 未执行检查

- 未启动 Android 模拟器；
- 未使用真实 OpenAI/ChatGPT 账号；
- 未向真实 OpenAI 端点发送任何请求；
- 未执行真实 OAuth、Token 刷新或模型请求；
- 未运行 `connectedAndroidTest`；
- 未重复执行已知会崩溃的完整 Debug Lint；
- 未重新打包 APK，统一留到 Phase 5。

以上边界符合既定计划；真实协议可用性和系统行为留到 Phase 6。

## 8. 遗留风险与下一阶段要求

- `NB-14`：服务端省略 `expires_in` 时，存储 JSON 不生成 `expire_at`；这与现有
  浏览器 OAuth 行为兼容，但 `validAccessToken()` 不会主动按时间刷新。Phase 5 必须
  使用含 expiry 的模拟过期 Token 验证完整刷新路径。
- 当前设备授权端点仍不是稳定公开集成 API，交付前必须重新复核。
- Phase 3 必须实现首次立即轮询、15 分钟轮询总上限、有限网络重试、重复点击幂等、
  generation 防迟到结果和一次性内存 Token 移交。
- Token exchange 不应盲目自动重试：响应丢失时无法判断一次性 authorization code
  是否已被服务端消费，Phase 3 应把该网络错误明确交给用户重新发起。
