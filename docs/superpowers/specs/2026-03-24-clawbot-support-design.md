# ClawBot Support Design

**Date:** 2026-03-24
**Project:** Andclaw
**Scope:** 在现有 Telegram 远程控制基础上，为 Android 端原生集成基于 iLink API 的微信 ClawBot 通道，并同步整理远程通道抽象，避免继续把通道逻辑堆叠在 `AgentController` 中。

---

## 1. Background

Andclaw 当前已经支持通过 Telegram Bot 远程下发任务、查询状态、停止任务，并在截图、拍照、录像、录音等场景自动把媒体回传到 Telegram。

现有实现的问题是：

- 远程通道逻辑基本直接写在 `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- `AgentController` 同时承担 Agent 编排、Telegram 轮询、命令解析、媒体回传、桥接状态管理等多种职责
- 如果直接按同样方式把微信 ClawBot 接进去，代码会进一步耦合，后续维护成本明显上升

用户希望增加微信 ClawBot 支持，并且采用 App 内置直连方案，而不是依赖外部代理服务。

参考实现：

- `参考源码/wechat-acp`：提供基于 `https://ilinkai.weixin.qq.com` 的 Bot 登录、长轮询和消息回发链路
- `参考源码/weclaw-proxy`：提供分层设计思路，尤其是会话管理、`context_token` 更新与路由边界

---

## 2. Goals

- 为 Andclaw 增加第二条远程控制通道：微信 ClawBot
- 采用 App 内置直连方式接入 iLink API
- 复用现有 Agent 主循环，不重写 `Utils.callLLMWithHistory()`、动作执行、媒体保存等核心能力
- 在架构上把 Telegram 与 ClawBot 抽象成统一的远程桥接通道
- 首版能力尽量对齐 Telegram：
  - 文本消息触发 Agent
  - `/status`
  - `/stop`
  - 文本结果回传
  - 截图、拍照、录像、录音媒体回传
  - typing 状态

---

## 3. Non-Goals

- 不引入外部 `weclaw-proxy` 服务作为运行时依赖
- 不实现多通道并发任务执行
- 不在首版中引入复杂路由、多模型分流或 Web 管理后台
- 不暴露过多 iLink 底层调试参数给终端用户
- 不重构 Agent 的 LLM 调用协议、动作 JSON 协议或历史记录数据库结构

---

## 4. High-Level Architecture

### 4.1 Architecture Summary

Andclaw 将保持“单一 Agent 核心 + 多远程通道入口”的结构：

- `AgentController` 继续只负责 Agent 主循环、动作执行和本地消息记录
- 新增远程桥接抽象层，统一 Telegram 与 ClawBot
- Telegram 逻辑从 `AgentController` 中迁出为 `TelegramBridge`
- 新增 `ClawBotBridge`，负责 iLink 登录、轮询、发送、会话上下文与媒体回传
- 新增 `RemoteBridgeManager`，统一管理多个远程通道的启动、停止、状态和入站分发

本设计在接口层面做如下明确决策，避免实现时返工：

- 新增 `IRemoteBridgeService`，由 `RemoteBridgeManager` 实现；`SetupKioskModeActivity` 不再直接依赖 `ITgBridgeService`
- 新增 `IRemoteChannelConfigService`，专门负责 Telegram 与 ClawBot 的配置读写
- `IAiConfigService` 保留为 AI Provider 配置接口，不再继续承载 Telegram/ClawBot 配置
- `AgentController` 不再实现桥接服务接口，也不再实现远程通道配置接口

### 4.2 Main Components

#### `AgentController`

职责：

- 处理本地 UI 输入或远程文本指令
- 驱动 `executeAgentStep()` 和 `handleAction()`
- 执行动作并收集结果
- 通过统一回传接口把文本/媒体回发给当前激活的远程会话

非职责：

- 不再直接轮询 Telegram
- 不再保存 Telegram 专属状态字段
- 不直接拼接某个通道的发送 API 调用

#### `RemoteBridge`

统一抽象远程通道的能力。接口至少包括：

- `start()`
- `stop()`
- `bridgeStatus`
- `sendTyping(session)`
- `sendText(session, text, replyHint?)`
- `sendPhoto(session, photoBytes, caption, fileName)`
- `sendVideo(session, videoBytes, caption, fileName)`
- `sendAudio(session, audioBytes, caption, fileName)`

#### `RemoteBridgeManager`

职责：

- 管理 `TelegramBridge` 与 `ClawBotBridge`
- 聚合桥接状态
- 接收各通道的入站消息并转交 `AgentController`
- 提供一个统一的出站回传入口给 `AgentController`

接口要求：

- 提供每个通道独立的状态流，而不是单一全局状态
- 至少暴露：
  - `telegramStatus`
  - `clawBotStatus`
  - `clawBotLoginStatus`
  - `startEligibleBridges()`
  - `stopAllBridges()`
  - `startTelegramBridgeIfConfigured()`
  - `startClawBotBridgeIfConfigured()`
  - `sendTyping(session)`
  - `sendText(session, text, replyHint?)`
  - `sendPhoto(session, photoBytes, caption, fileName)`
  - `sendVideo(session, videoBytes, caption, fileName)`
  - `sendAudio(session, audioBytes, caption, fileName)`

依赖方向明确如下：

- `AgentController` 只能依赖 `IRemoteBridgeService`
- `RemoteBridgeManager` 负责把出站请求路由到正确的 `RemoteBridge`
- `AgentController` 不直接依赖 `TelegramBridge` 或 `ClawBotBridge`
- `RemoteSessionStore` 只负责提供会话上下文，不负责执行发送动作

#### `TelegramBridge`

职责：

- 复用现有 `TgBotClient`
- 负责 Telegram 轮询、命令处理、状态同步和媒体发送
- 作为迁移基线，确保桥接抽象完成后 Telegram 功能不回归

#### `ClawBotBridge`

职责：

- 管理 ClawBot 登录态
- 调用 iLink REST API 获取二维码、轮询确认、拉消息、发消息、发 typing
- 管理 `context_token`
- 把微信消息转换为统一的远程入站模型

内部建议拆分：

- `ClawBotApiClient`
- `ClawBotAuthClient`
- `ClawBotPoller`
- `ClawBotBridge`
- `ClawBotModels`

#### `RemoteSessionStore`

职责：

- 按 `sessionKey` 保存远程会话上下文
- 维护当前会话与用户映射
- 持久化或缓存 `contextToken`、账号信息、最后入站时间等关键字段

首版约束：

- 不引入 Room 新表
- 使用“内存 + SharedPreferences”保存远程运行态
- 只持久化必要字段：`botToken`、`syncBuf`、`contextToken`、账号信息、白名单配置
- 聊天正文继续只由 `ChatMessageDao` 管理，`RemoteSessionStore` 不保存完整对话历史

---

## 5. Data Model Design

### 5.1 Unified Incoming Message

新增统一入站模型，例如 `RemoteIncomingMessage`：

- `channel`: `telegram` 或 `clawbot`
- `sessionKey`: 唯一会话标识
- `userId`: 远端用户标识
- `displayName`: 可选
- `text`: 文本内容
- `messageId`: 可选，Telegram 使用
- `replyToken`: 回包所需上下文
- `metadata`: 扩展字段

映射规则：

- Telegram:
  - `sessionKey = "tg:<chatId>"`
  - `userId = "<chatId>"`
  - `replyToken` 由 `chatId` 与 `messageId` 组成
- ClawBot:
  - `sessionKey = "wx:<accountId>:<fromUserId>"`
  - `userId = "<fromUserId>"`
  - `replyToken` 核心为 `contextToken`

### 5.2 Remote Session

新增 `RemoteSession`，至少保存：

- `channel`
- `sessionKey`
- `userId`
- `displayName`
- `replyToken`
- `accountId`（ClawBot）
- `messageId`（Telegram）
- `lastIncomingAt`
- `metadata`

首版最小必要字段以可执行为准，不强制一次把所有扩展字段做满；实现时至少保证：

- `channel`
- `sessionKey`
- `replyToken`
- `lastIncomingAt`
- `accountId`（ClawBot）
- `messageId`（Telegram）

### 5.3 ClawBot Auth State

新增 ClawBot 登录态模型，至少保存：

- `botToken`
- `baseUrl`
- `accountId`
- `userId`
- `botType`
- `savedAt`
- `syncBuf`

### 5.4 ClawBot Runtime Status

ClawBot 需要比 Telegram 更细的登录状态：

- `NOT_CONFIGURED`
- `LOGIN_REQUIRED`
- `QR_READY`
- `WAITING_CONFIRM`
- `CONNECTED`
- `DISCONNECTED`
- `STOPPED`

对外保留两层状态：

- 通用层：`BridgeStatus`
- ClawBot 细节层：`ClawBotLoginStatus`

`SetupKioskModeActivity` 不直接依赖单个 `BridgeStatus`，而是消费一个聚合 UI 状态，例如：

- `telegramStatus: BridgeStatus`
- `clawBotStatus: BridgeStatus`
- `clawBotLoginStatus: ClawBotLoginStatus`
- `clawBotStatusText: String`

---

## 6. ClawBot Protocol Design

### 6.1 Login Flow

参考 `参考源码/wechat-acp/src/weixin/auth.ts` 与 `参考源码/wechat-acp/src/weixin/api.ts`，Android 端首版登录流程：

1. 设置页点击“开始微信登录”
2. 调用 `ilink/bot/get_bot_qrcode`
3. 获取二维码信息并在 App 内展示
4. 轮询 `ilink/bot/get_qrcode_status`
5. 状态从 `wait` / `scaned` 变化到 `confirmed`
6. 读取并保存：
   - `bot_token`
   - `baseurl`
   - `ilink_bot_id`
   - `ilink_user_id`
7. 启动 `ClawBotBridge`

要求：

- 不使用终端二维码渲染
- 优先在设置页直接展示二维码
- 支持“重新登录”，强制清空旧 token 后重新开始
- 支持“清除登录状态”

### 6.2 Polling Flow

参考 `参考源码/wechat-acp/src/weixin/monitor.ts` 与 `参考源码/weclaw-proxy/internal/weixin/poller.go`：

- 长轮询 `ilink/bot/getupdates`
- 请求中携带 `get_updates_buf`
- 每次成功返回后更新并持久化 `syncBuf`
- 如遇登录态失效（例如 session expired），状态切回需要重新登录

### 6.3 Send Flow

参考 `参考源码/wechat-acp/src/weixin/send.ts`：

- 文本回发通过 `ilink/bot/sendmessage`
- 需要携带 `context_token`
- typing 通过 `getconfig` 获取 `typing_ticket` 后发送

### 6.4 Media Strategy

首版接口层面支持：

- photo
- video
- audio

如果微信媒体发送协议首版实现不稳定，允许在发送失败时降级为文本提示，例如：

- 文件已保存到本地路径
- 媒体回传失败，请在设备上查看

该降级策略不能影响 Agent 主链路。

---

## 7. Runtime Flow

### 7.1 Incoming Data Flow

1. Telegram 或 ClawBot 收到文本消息
2. 具体桥接实现转换为 `RemoteIncomingMessage`
3. `RemoteBridgeManager` 更新 `RemoteSessionStore`
4. 通用命令处理：
   - `/status`
   - `/stop`
5. 普通文本消息交给 `AgentController`
6. `AgentController` 设置 `activeRemoteSession`
7. Agent 开始执行

补充约束：

- 本地 UI 直接启动 Agent 时，`activeRemoteSession` 必须为 `null`
- 只有远程消息触发的任务才允许走远程回传
- `activeRemoteSession == null` 时，任何文本/媒体结果都不得发送到 Telegram 或 ClawBot

### 7.2 Outgoing Data Flow

1. `AgentController` 在执行前发送 typing
2. 执行动作，得到文本或媒体结果
3. 通过统一回传接口把结果发送到当前 `activeRemoteSession`
4. ClawBot 自动附带最新 `contextToken`
5. Telegram 复用 `chatId` / `messageId`

### 7.3 Command Semantics

首版所有远程通道统一支持：

- 普通文本：触发 Agent 任务
- `/status`：返回运行状态、当前任务、来源会话信息
- `/stop`：停止当前任务

不支持复杂 slash 命令扩展。

兼容性约束：

- 首版仅把以 `/` 开头的命令视为命令
- 非 slash 文本一律视为普通任务输入
- 这意味着微信端如果后续存在非 slash 命令习惯，再单独扩展，不在首版处理

### 7.4 Concurrency Policy

首版保持单任务执行模型：

- 同一时刻只允许一个 Agent 任务运行
- 如果已有任务运行，新入站文本返回“当前任务执行中，请稍后”
- 不实现会话级抢占或并发执行

这样可以降低多通道接入后的状态复杂度。

该策略对 Telegram 与 ClawBot 一视同仁，属于 Telegram 的行为调整；回归测试必须验证这一变化不会引发误判。

---

## 8. UI / Configuration Design

### 8.1 `AiSettingsActivity`

从“AI 配置 + Telegram 配置”扩展为：

1. AI API 配置
2. Telegram 通道配置
3. ClawBot 配置

ClawBot 配置区建议包含：

- 状态文本
- Bot Type（默认 `3`）
- 微信白名单用户 ID（可选）
- 开始登录 / 重新登录按钮
- 清除登录状态按钮
- 二维码展示区域
- 登录结果提示区域

不建议暴露以下底层协议参数：

- `base_url`
- `cdn_base_url`
- `channel_version`
- `sync_buf`
- `typing_ticket`

### 8.2 `SetupKioskModeActivity`

从当前单一 `Telegram 连接` 卡片扩展为两个卡片：

- `Telegram 连接`
- `ClawBot 连接`

展示状态：

- 未配置
- 未登录
- 登录中
- 已连接
- 已停止
- 连接失败

按钮行为：

- Telegram：去配置
- ClawBot：去登录

启动策略明确如下：

- 设备 Owner 切换为开启时，`RemoteBridgeManager.startEligibleBridges()` 启动所有已配置且可启动的通道
- 设备 Owner 切换为关闭时，`RemoteBridgeManager.stopAllBridges()` 停止所有远程通道
- Telegram 与 ClawBot 的启动失败相互独立，一个通道失败不能阻塞另一个通道进入可用状态

---

## 9. File Layout Proposal

建议新增目录结构：

- `app/src/main/java/com/andforce/andclaw/bridge/`
  - `RemoteBridge.kt`
  - `RemoteBridgeManager.kt`
  - `RemoteSessionStore.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/telegram/`
  - `TelegramBridge.kt`
  - `TgBotClient.kt`（可迁移或暂时保留原位置）
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/`
  - `ClawBotApiClient.kt`
  - `ClawBotAuthClient.kt`
  - `ClawBotPoller.kt`
  - `ClawBotBridge.kt`
  - `ClawBotModels.kt`（仅存放 app 内部协议模型）
- `services/src/main/java/com/base/services/`
  - 新增 `IRemoteBridgeService.kt`
  - 新增 `IRemoteChannelConfigService.kt`
  - 新增 `RemoteBridgeModels.kt`（`RemoteSession`、`RemoteChannel`、`RemoteIncomingMessage` 等跨模块共享类型）
  - 新增 `ClawBotSharedModels.kt`（`ClawBotAuthState`、`ClawBotLoginStatus` 等跨模块共享类型）
  - 收缩 `IAiConfigService.kt` 为纯 AI 配置接口
- `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/`
  - 更新 `AiSettingsActivity.kt`
  - 更新 `SetupKioskModeActivity.kt`
- `mdm/src/main/res/layout/`
  - 更新 `activity_ai_settings.xml`
  - 更新 `activity_setup_kiosk_layout.xml`

Telegram 文件迁移策略明确如下：

- 首版不强制迁移 `TgBotClient.kt` 的物理文件位置
- 先完成职责迁移，确保 Telegram 行为回归无误
- 目录整理可放到后续清理提交

服务接口最小方法表明确如下：

- `IRemoteBridgeService`
  - `val telegramStatus: StateFlow<BridgeStatus>`
  - `val clawBotStatus: StateFlow<BridgeStatus>`
  - `val clawBotLoginStatus: StateFlow<ClawBotLoginStatus>`
  - `fun startEligibleBridges()`
  - `fun stopAllBridges()`
  - `fun startTelegramBridgeIfConfigured()`
  - `fun startClawBotBridgeIfConfigured(forceRelogin: Boolean = false)`
  - `suspend fun sendTyping(session: RemoteSession)`
  - `suspend fun sendText(session: RemoteSession, text: String, replyHint: String? = null)`
  - `suspend fun sendPhoto(session: RemoteSession, bytes: ByteArray, caption: String? = null, fileName: String = "photo.png")`
  - `suspend fun sendVideo(session: RemoteSession, bytes: ByteArray, caption: String? = null, fileName: String = "video.mp4")`
  - `suspend fun sendAudio(session: RemoteSession, bytes: ByteArray, caption: String? = null, fileName: String = "audio.m4a")`

- `IRemoteChannelConfigService`
  - `val tgToken: String`
  - `fun getTgChatId(): Long`
  - `fun setTgToken(token: String)`
  - `fun setTgChatId(chatId: Long)`
  - `fun getClawBotBotType(): String`
  - `fun setClawBotBotType(botType: String)`
  - `fun getClawBotAllowedUserId(): String`
  - `fun setClawBotAllowedUserId(userId: String)`
  - `fun loadClawBotAuthState(): ClawBotAuthState?`
  - `fun saveClawBotAuthState(state: ClawBotAuthState)`
  - `fun clearClawBotAuthState()`
  - `fun loadClawBotSyncBuf(): String`
  - `fun saveClawBotSyncBuf(value: String)`

---

## 10. Error Handling

### 10.1 ClawBot Login Errors

- 二维码获取失败：展示错误文本，保持在“未登录”
- 二维码过期：允许重新发起登录
- token 保存失败：提示并停留在未连接状态

### 10.2 Polling Errors

- 网络波动：自动重试并显示“连接失败”
- token/session 失效：切回“需重新登录”
- `syncBuf` 持久化失败：记录日志并触发保守重拉策略

### 10.3 Message Errors

- ClawBot 入站缺少 `contextToken`：该条消息不启动 Agent，记录本地错误日志，并把桥接状态标记为异常待恢复
- 媒体发送失败：降级为文本提示
- typing 失败：忽略，不影响主流程

需要统一替换的远程出站调用点包括：

- `AgentController.addMessage(...)` 中当前对 Telegram 的文本推送
- `performConfirmedAction(...)` 中截图后的图片回传
- `performConfirmedAction(...)` 中拍照后的图片回传
- `performConfirmedAction(...)` 中录像完成后的视频回传
- `performConfirmedAction(...)` 中录音完成后的音频回传
- `performConfirmedAction(...)` 中录屏结束后的视频回传

### 10.4 Agent Busy Errors

- 当前已有任务时，新入站文本返回忙碌提示
- `/stop` 必须可跨通道停止当前任务

---

## 11. Testing Strategy

### 11.1 Unit / Logic-Level Verification

- `RemoteSessionStore` 的会话读写与更新时间逻辑
- Telegram / ClawBot 入站消息到统一模型的转换
- `/status`、`/stop` 命令分发
- ClawBot 登录状态机
- `contextToken` 更新覆盖逻辑

### 11.2 Manual Verification

#### Telegram Regression

- 输入文本任务
- `/status`
- `/stop`
- 截图回传
- 拍照回传
- 录像回传
- 录音回传

#### ClawBot Validation

- 开始登录后二维码正常展示
- 扫码后状态切换正确
- 文本消息可触发 Agent
- `/status` 返回正常
- `/stop` 返回正常
- 文本结果回传成功
- 至少验证一项媒体回传，并确认失败时能正确降级

---

## 12. Incremental Delivery Plan

建议按以下顺序实现：

1. 落通用远程桥接抽象
2. 从 `AgentController` 中迁出 Telegram 逻辑
3. 让 Telegram 跑在新抽象上，做回归验证
4. 增加 ClawBot 配置与登录 UI
5. 打通二维码登录与状态持久化
6. 打通长轮询与文本消息闭环
7. 补 `/status` 与 `/stop`
8. 最后补媒体回传和 typing

---

## 13. Acceptance Criteria

本需求完成时，应满足：

- Telegram 功能不回归
- `AgentController` 中不再保留 Telegram 专属轮询实现
- 设置页可完成 ClawBot 登录
- Kiosk 页面可展示 Telegram 与 ClawBot 各自状态
- 微信文本消息可触发 Agent
- `/status` 与 `/stop` 在微信通道可用
- 至少文本回传稳定，媒体回传具备能力或清晰降级
- `contextToken` 按会话正确维护，不出现串线程回复

---

## 14. Risks and Mitigations

### 风险 1：`AgentController` 回传逻辑分散

缓解：

- 在重构早期先统一收口所有远程回传调用点

### 风险 2：ClawBot 状态持久化不完整

缓解：

- token 与 `syncBuf` 一并保存
- 登录态与轮询状态分开管理

### 风险 3：`contextToken` 使用错误导致串会话

缓解：

- 按 `sessionKey` 持久化最新 `contextToken`
- 只从当前 `RemoteSession` 读取回包上下文

### 风险 4：微信媒体协议实现成本高于预期

缓解：

- 先保证文本主链路
- 媒体发送失败时统一文本降级

---

## 15. Recommendation

推荐采用“轻量桥接抽象 + App 内置 ClawBot 直连”的方案：

- 充分复用当前 Agent 主循环
- 明显降低 `AgentController` 的耦合程度
- 兼顾首版可交付速度与后续可维护性
- 不引入外部服务依赖，符合用户当前目标
