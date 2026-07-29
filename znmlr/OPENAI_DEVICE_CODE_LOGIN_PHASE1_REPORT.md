# OpenAI 设备码登录 Phase 1 验收报告

## 1. 结论

- 状态：通过
- 执行时间：2026-07-30 02:25～02:39（Asia/Shanghai）
- 基线提交：`9f64e60a81bfe9ffdee72620523926d1910fa127`
- 阶段提交：本报告所在的 Phase 1 独立提交
- 是否允许进入 Phase 2：是

Phase 1 已封住设备码流程会复用的日志与凭据删除边界。5 个直接相关测试类共
18 项测试全部通过；完整 JVM 测试从 Phase 0 的 390 项增加到 405 项，失败仍为相同
3 个测试套件中的 39 项，没有新增或恶化。

## 2. 关键修改

### 2.1 敏感正文脱敏

`OAuthManager.sanitizeBody()` 现在覆盖：

- `access_token`
- `refresh_token`
- `id_token`
- `api_key` / `key`
- `client_secret`
- `device_code`
- `user_code`
- `device_auth_id`
- `authorization_code`
- `code_verifier`
- `code_challenge`

有效 JSON 会递归遍历对象和数组；只要键名敏感，就替换整个值，而不是只替换第一个
字符串片段。JSON 解析失败但正文仍包含敏感 JSON 键时，整段正文被保守遮蔽。
Form URL Encoded 正文中的敏感字段也会被遮蔽。键名匹配不区分大小写。

### 2.2 OpenAI Token 交换错误收口

- 成功和失败响应都只记录 HTTP 状态码与正文长度，不记录正文；
- 非 2xx 使用只包含状态码的 `OAuthTokenExchangeException`；
- 2xx 但 JSON 无效时使用固定的 `OAuthTokenResponseException`；
- JSON 解析器的原始异常不作为 cause 保留，避免异常消息回显服务端正文；
- 状态校验和 JSON 解析提取成生产路径实际调用的纯函数，测试直接经过同一入口。

### 2.3 浏览器 OAuth 回调日志

- 授权 URL 中的 `state` 与 `code_challenge` 均被遮蔽；
- callback request line 进入 logcat 前移除完整 query；
- Redirect Activity 不再记录完整 URI 或本地转发 URL；
- URI、URL 和回调处理异常只记录异常类型，不记录 Throwable、message 或堆栈，
  防止解析异常再次带出 code/state；
- 当前既有的 state mismatch 行为没有在本阶段顺带改变，已记为 `NB-11`。

### 2.4 删除 OpenAI OAuth Provider 的凭据清理

删除前先捕获目标 `ProviderInstance`，保存配置后：

1. OpenAI OAuth 实例清除该 instance ID 的：
   - Token JSON；
   - 手动 Bearer Token；
   - PKCE verifier；
   - 预留 state；
   - account ID；
   - plan type；
2. 始终清除 ProviderRepository 中的 API Key 镜像；
3. 所有 OAuth preference key 都由统一函数附加目标 instance ID；
4. 删除 API Key Provider、其他 Provider 或陈旧 ID 时，不会选中其他实例的 OAuth
   namespace。

生产删除路径被提取为可注入回调的纯调度函数；JVM 测试用内存集合执行该真实调度，
同时复用生产的 OpenAI key mapping，验证目标清空与其他实例隔离。

## 3. 修改文件

产品代码：

- `src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt`
- `src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt`
- `src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt`
- `src/android/app/src/main/java/com/openminis/app/auth/OAuthRedirectActivity.kt`
- `src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt`

测试：

- `src/android/app/src/test/java/com/openminis/app/auth/OAuthLogRedactionTest.kt`
- `src/android/app/src/test/java/com/openminis/app/auth/OAuthCallbackLogRedactionTest.kt`
- `src/android/app/src/test/java/com/openminis/app/auth/OpenAIOAuthErrorTest.kt`
- `src/android/app/src/test/java/com/openminis/app/auth/OpenAIProviderCredentialCleanupTest.kt`
- `src/android/app/src/test/java/com/openminis/app/data/repository/ProviderCredentialCleanupTest.kt`

## 4. 验证证据

### 4.1 Focused JVM tests

执行 5 个测试类：

- `OAuthLogRedactionTest`：8 项；
- `OAuthCallbackLogRedactionTest`：3 项；
- `OpenAIOAuthErrorTest`：2 项；
- `OpenAIProviderCredentialCleanupTest`：2 项；
- `ProviderCredentialCleanupTest`：3 项。

结果：18 tests，0 failure，0 error，0 skipped；Gradle
`BUILD SUCCESSFUL in 3m 12s`。

覆盖：

- 所有设备授权字段；
- 嵌套对象、数组、大小写、escaped string、primitive、无效 JSON 和 form body；
- callback query 与授权 URL 脱敏；
- Token 非 2xx 与无效 JSON 的用户异常；
- OpenAI 全部凭据 key；
- 删除目标实例、其他实例隔离、API Key Provider 和陈旧 ID。

### 4.2 完整 JVM 回归

执行：

```bash
./gradlew --no-daemon --console=plain :app:testDebugUnitTest
```

结果：405 tests completed，39 failed，约 36 秒。与 Phase 0 完全一致：

- `AnthropicProviderTest`：24 项失败，仍为缺少
  `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`；
- `OpenAIProviderTest`：11 项失败，仍为 MockWebServer 路径的
  `Server returned an empty response`；
- `TerminalSanitizerTest`：4 项失败，仍为 CR folding 比较差异。

新增的 15 个测试净增项全部通过，没有新增失败套件、失败数量或错误签名。

### 4.3 其他检查

- 修改后的 main/debug Kotlin 源码编译：通过；
- `KimiDeviceFlowTest` 包含在完整回归中：通过；
- 敏感日志静态搜索：未发现 OpenAI `responseBody.take`、完整 response body 异常、
  raw callback request、完整 Redirect URI、完整 state/expectedState 日志；
- `git diff --check`：通过；
- 独立安全复审：第一轮发现的 JSON aggregate 与异常堆栈泄漏均已修正；第二轮确认
  代码层面三类阻塞解除。

## 5. 未执行检查

- 未启动 Android 模拟器；
- 未使用真实 OpenAI/ChatGPT 账号；
- 未执行真实 OAuth、Token 刷新或模型请求；
- 未运行 `connectedAndroidTest`；
- 本 Phase 未重复运行已知会由 3 个分析任务崩溃的完整 Debug Lint；
- 本 Phase 未重新打包 APK；`testDebugUnitTest` 已实际重新编译修改后的 Debug
  Kotlin 主源码，完整 APK 会在 Phase 5 统一构建。

以上边界符合既定计划；真实设备行为留到 Phase 6。

## 6. 遗留风险

- `NB-11`：既有 OpenAI 浏览器 OAuth 在 state 不匹配时仍继续交换授权码；
- `NB-12`：既有浏览器 OAuth 与删除 Provider 同时发生时，存在迟到写入孤立凭据的
  理论竞态；
- `NB-13`：通用字段 `key` 的安全遮蔽可能降低少量日志可读性。

这些问题不阻塞设备授权协议核心。新设备码路径必须在 Phase 3/4 通过 attempt 身份、
取消和严格提交顺序避免迟到凭据写入。

## 7. 下一阶段前置条件

Phase 2 必须：

1. 只实现单次设备授权协议操作，不提前加入持续轮询协调；
2. HTTP client、端点和响应分类可注入、可用 MockWebServer 测试；
3. Token 结果只保存在内存，不能在 Provider 保存前写长期凭据；
4. 所有异常继续使用 Phase 1 建立的正文与日志安全边界。
