# OpenMinis 个人维护仓库

> 这是 `yangyunzhao/OpenMinis` 的个人 Fork，不是 OpenMinis 官方仓库，也不代表
> 官方发布或官方支持版本。

本仓库从 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 派生，
用于同步官方公开源码、维护个人修改、保存可复现的构建与验收记录。上游原始
README 已保留为 [上游官方项目说明](上游官方项目说明.md)；官方网站、产品能力、
官方构建方式和下载入口请优先以该文件及官方仓库为准。

## 两个 GitHub 仓库的关系

| 角色 | 仓库 | 本地远端名 | 用途 | 写入规则 |
|---|---|---|---|---|
| 官方上游 | [`OpenMinis/OpenMinis`](https://github.com/OpenMinis/OpenMinis) | `upstream` | 获取官方公开镜像的更新 | 只获取；本地 push URL 设为 `DISABLED` |
| 个人 Fork | [`yangyunzhao/OpenMinis`](https://github.com/yangyunzhao/OpenMinis) | `origin` | 保存个人维护提交、功能分支和对应源码 | 个人分支只推送到这里 |

两个仓库不是双向共同开发关系：

```text
官方公开镜像 OpenMinis/OpenMinis
              │
              │ fetch / 同步官方更新
              ▼
本地 upstream/main
              │
              │ 人工检查后同步到个人 main
              ▼
个人 main ──创建或更新──► feature/openai-device-code-login
                                  │
                                  │ push
                                  ▼
                        个人远端 yangyunzhao/OpenMinis
```

- `upstream` 是官方更新来源，不保存个人提交。
- `origin` 是个人 Fork，保存个人维护所需的分支和提交。
- 功能开发不直接在 `main` 上进行；当前功能分支是
  `feature/openai-device-code-login`。
- 同步上游时先刷新 `upstream/main`，理解双方修改后再决定 merge 或 rebase；
  发生冲突时不自动选择 `ours` 或 `theirs`。

更完整的远端配置、同步、冲突处理和 Windows 脚本说明见
[个人派生仓库与官方上游同步说明](znmlr/个人派生仓库与官方上游同步说明.md)。

本 README 当前随 `feature/openai-device-code-login` 功能分支交付。GitHub 默认分支
仍是个人 `main`，因此在功能分支尚未合入 `main` 前，个人仓库默认首页不会显示
这份说明；是否合入个人 `main` 由仓库所有者以后另行决定。

## Pull Request 和反馈政策

官方 [README](上游官方项目说明.md#community) 与
[贡献说明](CONTRIBUTING.md) 明确指出：`OpenMinis/OpenMinis` 是私有开发树的
公开镜像，**不接受 Pull Request**。即使向官方镜像创建 PR，也会被关闭，因为
该修改无法合入其私有开发树，并可能在下一次镜像同步时被覆盖。

因此，本仓库遵循以下边界：

- 不向官方 `upstream` 推送；
- 不向 `OpenMinis/OpenMinis` 创建 PR；
- 个人修改只提交并推送到个人 `origin`；
- 如需向原作者反馈功能建议，先整理不含敏感信息的说明，再由仓库所有者决定是否
  提交官方 Issue；
- 若个人仓库内部将来需要 PR，它只是个人分支的审查工具，不代表向官方提交代码。

## 当前个人修改

当前主要功能是为 Android 版官方 OpenAI Provider 增加设备码登录，并保留原有
API Key 与浏览器回调登录方式。主要边界包括：

- 用户主动选择设备码登录，不在进入页面时自动发起；
- 通过系统浏览器完成授权，不依赖手机本地 `localhost` 回调；
- 显示、打开或复制登录网址，并原样显示、复制一次性授权码；
- 单一有效登录尝试、取消、超时、有限重试和迟到结果保护；
- 认证成功后仍由用户明确保存 Provider；
- Provider、OAuth Token 和模型配置按严格顺序提交，失败时补偿清理；
- 复用既有 OAuth Token 自动刷新路径，并补充并发单飞和安全删除边界；
- 已保存 Provider 不提供设备码重新登录入口；如需改变登录方式，删除后重新新建。

相对官方上游的文件级修改、比较基准和重新生成命令见
[个人派生仓库相对官方上游修改清单](znmlr/个人派生仓库相对官方上游修改清单.md)。

## 当前验证状态

- Phase 0～Phase 5：基线、安全、协议、状态协调、界面集成、自动化回归和
  Debug APK 构建已完成。
- Phase 6：用户已在真实 Android 设备上完成安装、设备码授权、Provider 保存、
  应用重启、模型加载和一次明确同意的最小真实请求。
- Phase 7：正在整理个人仓库首页、修改文件记录、最终交付证据与个人远端状态。
- 取消重试、网络异常、旋转、锁屏、进程重建和自然 Token 刷新等未执行真机场景
  会继续如实列为未执行验证；自动化结果不能冒充真机结果。
- 当前有两项不阻塞验收的极低优先级界面建议：设备码入口的“推荐”提示不够醒目，
  以及已保存 Provider 主要通过绿色圆点和“退出登录”表达登录状态。

详细证据：

- [需求与决策](znmlr/开放人工智能设备码登录需求与决策.md)
- [分阶段实施计划](znmlr/开放人工智能设备码登录分阶段实施计划.md)
- [持续工作记录](znmlr/开放人工智能设备码登录持续工作记录.md)
- [第五阶段自动化回归与调试构建验收报告](znmlr/开放人工智能设备码登录第五阶段自动化回归与调试构建验收报告.md)
- [第六阶段真机验收清单](znmlr/开放人工智能设备码登录第六阶段真机验收清单.md)
- [第七阶段交付验收报告](znmlr/开放人工智能设备码登录第七阶段交付验收报告.md)

## 克隆个人 Fork

```powershell
git clone --recurse-submodules https://github.com/yangyunzhao/OpenMinis.git
Set-Location OpenMinis

git remote add upstream https://github.com/OpenMinis/OpenMinis.git
git remote set-url --push upstream DISABLED
git fetch upstream

git switch feature/openai-device-code-login
git submodule update --init --recursive
```

检查远端角色：

```powershell
git remote -v
```

预期 `origin` 的 fetch/push 均指向个人 Fork；`upstream` 的 fetch 指向官方仓库，
push 显示为 `DISABLED`。

## 同步官方更新

在 PowerShell 中可以使用：

```powershell
.\znmlr\Sync-Upstream.ps1
```

脚本和人工流程的完整说明见
[个人派生仓库与官方上游同步说明](znmlr/个人派生仓库与官方上游同步说明.md)。

同步时需要特别注意：

1. 先确认工作区干净，并获取最新 `upstream/main`；
2. 先同步个人 `main`，再把 `main` 合入或变基到功能分支；
3. 已推送分支如需 rebase，只使用 `--force-with-lease`，不得使用 `--force`；
4. 冲突必须逐文件理解和验证；不确定时保留现场或安全中止本次 merge/rebase；
5. 同步后重新执行受影响测试，不以“Git 已合并成功”代替功能验证。

### README 的特殊同步风险

官方仓库仍使用根目录 `README.md`，而本 Fork 已将当时的官方内容重命名为
`上游官方项目说明.md`，并在 `README.md` 放置个人说明。上游以后修改 README 时，
Git 可能把官方修改与个人首页视为同一路径修改并产生冲突。

每次同步上游都应人工完成以下检查：

1. 保留个人 `README.md` 的个人 Fork 定位；
2. 单独查看 `upstream/main:README.md` 的最新变化；
3. 将需要保留的官方内容更新到 `上游官方项目说明.md`；
4. 检查图片、章节锚点和相对链接是否仍然有效；
5. 不配置静默的 `merge=ours`，避免漏掉重要的官方说明更新。

## 构建与 APK

官方构建前置条件和命令见 [BUILDING.md](BUILDING.md)。个人构建和验收记录以
Phase 报告为准；分发 APK 时应同时记录：

- APK 文件名和大小；
- SHA-256；
- 对应源码提交；
- 实际执行的测试；
- 未执行验证和已知风险。

个人 Debug APK 不等于官方签名版本。若设备已有不同签名的 OpenMinis，覆盖安装
会失败；卸载可能删除应用数据，必须先确认数据和备份情况。

## 安全、隐私与许可证

- 不得把一次性授权码、访问令牌、刷新令牌、PKCE、真实账号信息或未脱敏日志提交
  到源码、文档、Issue 或个人远端。
- 个人构建不等于经过官方审计，也不获得官方支持承诺。
- 本项目按 [GPLv3](LICENSE) 发布，并包含
  [第三方许可证清单](THIRD_PARTY_LICENSES.md) 中列出的依赖。
- 分发修改后的 APK 或其他二进制时，应按适用的 GPLv3 条款提供对应源码、保留
  许可证与版权声明，并继续遵守相应许可证。本段仅用于说明本仓库的维护原则，
  不构成法律意见。

## 相关入口

- [上游官方项目说明](上游官方项目说明.md)
- [官方构建说明](BUILDING.md)
- [官方贡献政策](CONTRIBUTING.md)
- [第三方许可证](THIRD_PARTY_LICENSES.md)
- [个人派生仓库与官方上游同步说明](znmlr/个人派生仓库与官方上游同步说明.md)
- [个人 Fork 修改文件清单](znmlr/个人派生仓库相对官方上游修改清单.md)
