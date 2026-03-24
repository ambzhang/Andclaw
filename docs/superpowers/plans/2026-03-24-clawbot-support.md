# ClawBot Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Andclaw 增加 App 内置直连的微信 ClawBot 远程通道，并把现有 Telegram 远程桥接从 `AgentController` 中抽离到统一的多通道桥接架构中。

**Architecture:** 保留 `AgentController` 作为唯一 Agent 核心，新增 `IRemoteBridgeService`、`IRemoteChannelConfigService` 与 `RemoteBridgeManager` 管理多个远程通道；Telegram 先迁移到新抽象上保证零回归，再增量接入基于 iLink API 的 ClawBot 登录、长轮询、文本回传和媒体回传能力。

**Tech Stack:** Kotlin、Android、Koin、OkHttp、Coroutines、StateFlow、SharedPreferences、Gradle

---

## File Map

### New Files

- `services/src/main/java/com/base/services/IRemoteBridgeService.kt`
- `services/src/main/java/com/base/services/IRemoteChannelConfigService.kt`
- `services/src/main/java/com/base/services/RemoteBridgeModels.kt`
- `services/src/main/java/com/base/services/ClawBotSharedModels.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridge.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/RemoteSessionStore.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/TelegramBridge.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotModels.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotApiClient.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotAuthClient.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotPoller.kt`
- `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotBridge.kt`
- `app/src/test/java/com/andforce/andclaw/bridge/RemoteSessionStoreTest.kt`
- `app/src/test/java/com/andforce/andclaw/bridge/clawbot/ClawBotParsingTest.kt`

### Modified Files

- `services/src/main/java/com/base/services/IAiConfigService.kt`
- `services/src/main/java/com/base/services/ITgBridgeService.kt` or its consumers if deprecated
- `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- `app/src/main/java/com/andforce/andclaw/App.kt`
- `app/src/main/java/com/andforce/andclaw/TgBotClient.kt`
- `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/AiSettingsActivity.kt`
- `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/SetupKioskModeActivity.kt`
- `mdm/src/main/res/layout/activity_ai_settings.xml`
- `mdm/src/main/res/layout/activity_setup_kiosk_layout.xml`
- `README.md`

### Manual Verification Targets

- `Telegram` 文本、`/status`、`/stop`、截图、拍照、录像、录音回传
- `ClawBot` 二维码登录、长轮询、文本回传、`/status`、`/stop`

---

### Task 1: 收口服务接口与配置边界

**Files:**
- Create: `services/src/main/java/com/base/services/IRemoteBridgeService.kt`
- Create: `services/src/main/java/com/base/services/IRemoteChannelConfigService.kt`
- Create: `services/src/main/java/com/base/services/RemoteBridgeModels.kt`
- Create: `services/src/main/java/com/base/services/ClawBotSharedModels.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridge.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/App.kt`

- [ ] **Step 1: 先写接口草图与依赖边界说明**

```kotlin
interface IRemoteBridgeService {
    val telegramStatus: StateFlow<BridgeStatus>
    val clawBotStatus: StateFlow<BridgeStatus>
    val clawBotLoginStatus: StateFlow<ClawBotLoginStatus>
    fun startEligibleBridges()
    fun stopAllBridges()
    suspend fun sendText(session: RemoteSession, text: String, replyHint: String? = null)
}

interface IRemoteChannelConfigService {
    val tgToken: String
    fun getTgChatId(): Long
    fun setTgToken(token: String)
    fun loadClawBotAuthState(): ClawBotAuthState?
}
```

- [ ] **Step 2: 把跨模块共享类型放到 `services`**

```kotlin
enum class RemoteChannel { TELEGRAM, CLAWBOT }

data class RemoteSession(
    val channel: RemoteChannel,
    val sessionKey: String,
    val replyToken: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val messageId: String? = null,
    val accountId: String? = null,
    val lastIncomingAt: Long = System.currentTimeMillis()
)

data class RemoteIncomingMessage(
    val channel: RemoteChannel,
    val sessionKey: String,
    val text: String,
    val replyToken: String? = null
)

enum class ClawBotLoginStatus {
    NOT_CONFIGURED, LOGIN_REQUIRED, QR_READY, WAITING_CONFIRM, CONNECTED, DISCONNECTED, STOPPED
}

data class ClawBotAuthState(
    val botToken: String,
    val baseUrl: String,
    val accountId: String,
    val userId: String,
    val botType: String,
    val savedAt: Long
)
```

- [ ] **Step 3: 先写 `RemoteBridge` 与 `RemoteBridgeManager` 最小骨架，避免后续 Koin 无法编译**

```kotlin
interface RemoteBridge

class RemoteBridgeManager : IRemoteBridgeService, IRemoteChannelConfigService {
    override val telegramStatus = MutableStateFlow(BridgeStatus.NOT_CONFIGURED)
    override val clawBotStatus = MutableStateFlow(BridgeStatus.NOT_CONFIGURED)
    override val clawBotLoginStatus = MutableStateFlow(ClawBotLoginStatus.NOT_CONFIGURED)
    override fun startEligibleBridges() = Unit
    override fun stopAllBridges() = Unit
    override suspend fun sendText(session: RemoteSession, text: String, replyHint: String?) = Unit
}
```

- [ ] **Step 4: 修改 `App.kt` 的 Koin 绑定，但暂时保留 `IAiConfigService` 现有签名不动**

```kotlin
single<IAiConfigService> { AgentController }
single { RemoteBridgeManager() }
single<IRemoteChannelConfigService> { get<RemoteBridgeManager>() }
single<IRemoteBridgeService> { get<RemoteBridgeManager>() }
```

- [ ] **Step 5: 编译服务接口相关代码**

Run: `./gradlew :services:compileDebugKotlin :app:compileDebugKotlin`

Expected: Kotlin 编译通过，无接口缺失错误。

- [ ] **Step 6: Commit**

```bash
git add services/src/main/java/com/base/services/IRemoteBridgeService.kt services/src/main/java/com/base/services/IRemoteChannelConfigService.kt services/src/main/java/com/base/services/RemoteBridgeModels.kt services/src/main/java/com/base/services/ClawBotSharedModels.kt app/src/main/java/com/andforce/andclaw/bridge/RemoteBridge.kt app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt app/src/main/java/com/andforce/andclaw/App.kt
git commit -m "refactor: add shared remote bridge service skeleton"
```

### Task 2: 新增统一远程模型与会话存储

**Files:**
- Create: `app/src/main/java/com/andforce/andclaw/bridge/RemoteSessionStore.kt`
- Test: `app/src/test/java/com/andforce/andclaw/bridge/RemoteSessionStoreTest.kt`

- [ ] **Step 1: 先写失败测试，覆盖会话读写与覆盖更新**

```kotlin
@Test
fun updates_reply_token_when_same_session_receives_new_message() {
    val store = RemoteSessionStore(FakePrefs())
    store.upsert(RemoteSession(sessionKey = "wx:a:u1", replyToken = "old"))
    store.upsert(RemoteSession(sessionKey = "wx:a:u1", replyToken = "new"))
    assertEquals("new", store.get("wx:a:u1")?.replyToken)
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andforce.andclaw.bridge.RemoteSessionStoreTest"`

Expected: FAIL，提示 `RemoteSessionStore` 或相关符号不存在。

- [ ] **Step 3: 基于 `services` 共享模型实现会话存储**

```kotlin
class RemoteSessionStore(...) {
    fun upsert(session: RemoteSession) { ... }
}
```

- [ ] **Step 4: 再次运行测试确保通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andforce.andclaw.bridge.RemoteSessionStoreTest"`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/bridge/RemoteSessionStore.kt app/src/test/java/com/andforce/andclaw/bridge/RemoteSessionStoreTest.kt
git commit -m "feat: add shared remote session models"
```

### Task 3: 改造 `AgentController` 的远程会话入口与出站出口

**Files:**
- Modify: `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/App.kt`
- Test: `app/src/test/java/com/andforce/andclaw/bridge/RemoteSessionStoreTest.kt`

- [ ] **Step 1: 识别所有 Telegram 直连调用点**

Search targets:
- `tgBotClient`
- `tgActiveChatId`
- `handleTelegramCommand`
- `sendPhoto(`
- `sendVideo(`
- `sendAudio(`

Expected: 列出 `AgentController.kt` 内所有需要改成统一出口的位置。

- [ ] **Step 2: 为 `AgentController` 增加统一远程会话字段**

```kotlin
private var activeRemoteSession: RemoteSession? = null
private lateinit var remoteBridgeService: IRemoteBridgeService
```

- [ ] **Step 3: 把文本与媒体回传改成只走 `IRemoteBridgeService`**

```kotlin
val session = activeRemoteSession
if (session != null) {
    scope.launch(Dispatchers.IO) {
        remoteBridgeService.sendText(session, "[$role] $content")
    }
}
```

- [ ] **Step 4: 明确本地 UI 会话不回传**

```kotlin
fun startAgent(input: String, remoteSession: RemoteSession? = null) {
    activeRemoteSession = remoteSession
}
```

- [ ] **Step 5: 编译验证 `AgentController`**

Run: `./gradlew :app:compileDebugKotlin`

Expected: `AgentController.kt` 无未解析引用，所有 Telegram 调用点被统一出口替换。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/AgentController.kt app/src/main/java/com/andforce/andclaw/App.kt
git commit -m "refactor: route agent remote replies through bridge service"
```

### Task 4: 把 Telegram 迁移到独立桥接实现

**Files:**
- Create: `app/src/main/java/com/andforce/andclaw/bridge/TelegramBridge.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/TgBotClient.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/App.kt`
- Modify: `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/SetupKioskModeActivity.kt`

- [ ] **Step 1: 先为 TelegramBridge 定义最小职责**

```kotlin
class TelegramBridge(
    private val config: IRemoteChannelConfigService,
    private val sessionStore: RemoteSessionStore,
    private val onIncomingMessage: suspend (RemoteIncomingMessage) -> Unit
)
```

- [ ] **Step 2: 把 Telegram 轮询与命令解析从 `AgentController` 搬到 `TelegramBridge`**

```kotlin
when (msg.text) {
    "/status" -> sendStatus(...)
    "/stop" -> stopAgent(...)
    else -> onIncomingMessage(msg.toRemoteIncomingMessage())
}
```

- [ ] **Step 3: 让 `RemoteBridgeManager` 接管 Telegram 的启动与状态**

Run: `./gradlew :app:compileDebugKotlin :mdm:compileDebugKotlin`

Expected: `SetupKioskModeActivity` 不再依赖单个 `ITgBridgeService.bridgeStatus`。

- [ ] **Step 4: 手动回归 Telegram 文本链路**

Run: 安装调试包后，发送文本、`/status`、`/stop`

Expected: 行为与旧版本一致，仅新增“任务忙碌时返回忙碌提示”。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/bridge/TelegramBridge.kt app/src/main/java/com/andforce/andclaw/TgBotClient.kt app/src/main/java/com/andforce/andclaw/AgentController.kt app/src/main/java/com/andforce/andclaw/App.kt mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/SetupKioskModeActivity.kt
git commit -m "refactor: extract telegram bridge from agent controller"
```

### Task 5: 实现 ClawBot 配置、登录态与协议模型

**Files:**
- Modify: `services/src/main/java/com/base/services/IRemoteChannelConfigService.kt`
- Modify: `services/src/main/java/com/base/services/ClawBotSharedModels.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotModels.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotApiClient.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotAuthClient.kt`
- Test: `app/src/test/java/com/andforce/andclaw/bridge/clawbot/ClawBotParsingTest.kt`

- [ ] **Step 1: 写解析测试，覆盖二维码响应与登录确认**

```kotlin
@Test
fun parses_confirmed_login_payload() {
    val json = """{"bot_token":"t","baseurl":"https://ilinkai.weixin.qq.com","ilink_bot_id":"b","ilink_user_id":"u"}"""
    val state = parseConfirmedLogin(json)
    assertEquals("t", state.botToken)
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andforce.andclaw.bridge.clawbot.ClawBotParsingTest"`

Expected: FAIL，提示解析函数或模型未定义。

- [ ] **Step 3: 实现模型与 API 封装**

```kotlin
data class ClawBotQrStatus(
    val state: String,
    val authState: ClawBotAuthState? = null
)
```

- [ ] **Step 4: 再次运行测试确保通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andforce.andclaw.bridge.clawbot.ClawBotParsingTest"`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotModels.kt app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotApiClient.kt app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotAuthClient.kt app/src/test/java/com/andforce/andclaw/bridge/clawbot/ClawBotParsingTest.kt services/src/main/java/com/base/services/IRemoteChannelConfigService.kt
git commit -m "feat: add ClawBot auth and API models"
```

### Task 6: 改造设置页与 Kiosk 状态页

**Files:**
- Modify: `services/src/main/java/com/base/services/IAiConfigService.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/App.kt`
- Modify: `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/AiSettingsActivity.kt`
- Modify: `mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/SetupKioskModeActivity.kt`
- Modify: `mdm/src/main/res/layout/activity_ai_settings.xml`
- Modify: `mdm/src/main/res/layout/activity_setup_kiosk_layout.xml`

- [ ] **Step 1: 在同一个提交里把 `mdm` 从 `IAiConfigService` 的 Telegram 字段迁移到 `IRemoteChannelConfigService`**

```kotlin
private val aiConfigService: IAiConfigService by inject()
private val remoteChannelConfigService: IRemoteChannelConfigService by inject()
private val remoteBridgeService: IRemoteBridgeService by inject()
```

- [ ] **Step 2: 再收缩 `IAiConfigService` 为纯 AI 配置接口**

```kotlin
interface IAiConfigService {
    val provider: String
    val apiUrl: String
    val apiKey: String
    val model: String
    val defaultApiKey: String
    fun updateConfig(provider: String, apiUrl: String, apiKey: String, model: String)
    fun saveProviderKey(provider: String, key: String)
    fun loadProviderKey(provider: String): String
}
```

- [ ] **Step 3: 增加 ClawBot 配置区与状态视图**

```xml
<TextView
    android:id="@+id/tvClawBotStatus"
    android:text="未登录" />
```

- [ ] **Step 4: 增加登录、重新登录、清除状态按钮和二维码展示区域**

```xml
<ImageView
    android:id="@+id/ivClawBotQr"
    android:visibility="gone" />
```

- [ ] **Step 5: 在 Activity 中接入 `IRemoteChannelConfigService` 与 `IRemoteBridgeService`**

Run: `./gradlew :mdm:compileDebugKotlin`

Expected: 设置页和 Kiosk 页编译通过，可显示 Telegram 与 ClawBot 两组状态。

- [ ] **Step 6: 手动验证页面交互**

Expected:
- 可看到 ClawBot 区域
- 点击登录后能进入请求二维码流程
- Kiosk 首页能展示双通道状态

- [ ] **Step 7: Commit**

```bash
git add services/src/main/java/com/base/services/IAiConfigService.kt app/src/main/java/com/andforce/andclaw/AgentController.kt app/src/main/java/com/andforce/andclaw/App.kt mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/AiSettingsActivity.kt mdm/src/main/java/com/afwsamples/testdpc/policy/locktask/SetupKioskModeActivity.kt mdm/src/main/res/layout/activity_ai_settings.xml mdm/src/main/res/layout/activity_setup_kiosk_layout.xml
git commit -m "refactor: move remote channel settings out of AI config service"
```

### Task 7: 打通 ClawBot 长轮询、文本消息与命令闭环

**Files:**
- Create: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotPoller.kt`
- Create: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotBridge.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/AgentController.kt`

- [ ] **Step 1: 先写最小轮询循环，处理 `syncBuf` 更新**

```kotlin
while (isActive) {
    val result = apiClient.getUpdates(syncBuf = config.loadClawBotSyncBuf())
    config.saveClawBotSyncBuf(result.nextSyncBuf)
}
```

- [ ] **Step 2: 把微信文本消息映射成统一 `RemoteIncomingMessage`**

```kotlin
RemoteIncomingMessage(
    channel = RemoteChannel.CLAWBOT,
    sessionKey = "wx:${accountId}:${fromUserId}",
    replyToken = contextToken,
    text = text
)
```

- [ ] **Step 3: 支持 `/status` 与 `/stop`**

Run: `./gradlew :app:compileDebugKotlin`

Expected: ClawBotBridge 编译通过，命令与文本链路统一接入 `AgentController`。

- [ ] **Step 4: 手动验证文本闭环**

Expected:
- 扫码登录成功后可收到微信消息
- 纯文本可触发 Agent
- `/status`、`/stop` 均能返回
- 同一时刻已有任务时，微信收到忙碌提示
- 具备可访问 `ilinkai.weixin.qq.com` 的网络与可扫码登录的微信环境

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotPoller.kt app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotBridge.kt app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt app/src/main/java/com/andforce/andclaw/AgentController.kt
git commit -m "feat: add ClawBot text bridge and command handling"
```

### Task 8: 补齐媒体回传、typing 与文档更新

**Files:**
- Modify: `app/src/main/java/com/andforce/andclaw/AgentController.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/bridge/TelegramBridge.kt`
- Modify: `app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotBridge.kt`
- Modify: `README.md`

- [ ] **Step 1: 把截图、拍照、录像、录音、录屏回传全部接入统一桥接出口**

```kotlin
remoteBridgeService.sendPhoto(session, bytes, caption = fileName, fileName = fileName)
```

- [ ] **Step 2: 为 Telegram 与 ClawBot 都补 typing**

```kotlin
remoteBridgeService.sendTyping(session)
```

- [ ] **Step 3: 对 ClawBot 媒体失败实现文本降级**

```kotlin
remoteBridgeService.sendText(session, "媒体已保存到本地，但微信回传失败：$path")
```

- [ ] **Step 4: 运行完整编译与单测**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :mdm:compileDebugKotlin`

Expected: 单测通过，`app` 与 `mdm` Kotlin 编译通过。

- [ ] **Step 5: 更新 README**

Add:
- ClawBot 登录说明
- 双通道支持说明
- 忙碌提示行为说明

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/andforce/andclaw/AgentController.kt app/src/main/java/com/andforce/andclaw/bridge/RemoteBridgeManager.kt app/src/main/java/com/andforce/andclaw/bridge/TelegramBridge.kt app/src/main/java/com/andforce/andclaw/bridge/clawbot/ClawBotBridge.kt README.md
git commit -m "feat: complete ClawBot media bridge and docs"
```

---

## Final Verification Checklist

- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `./gradlew :app:compileDebugKotlin :mdm:compileDebugKotlin`
- [ ] Telegram 文本、`/status`、`/stop` 回归验证
- [ ] Telegram 截图/拍照/录像/录音回归验证
- [ ] ClawBot 二维码登录验证
- [ ] ClawBot 文本消息闭环验证
- [ ] ClawBot `/status`、`/stop` 验证
- [ ] ClawBot 至少一项媒体回传或降级验证
