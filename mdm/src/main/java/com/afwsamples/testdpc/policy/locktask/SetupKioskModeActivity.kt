package com.afwsamples.testdpc.policy.locktask

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.UserManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.andforce.andclaw.DeviceAdminReceiver
import com.andforce.mdm.center.DeviceStatusViewModel
import com.andforce.mdm.center.AppUtils
import com.afwsamples.testdpc.DevicePolicyManagerGateway
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl
import com.afwsamples.testdpc.R
import com.afwsamples.testdpc.databinding.ActivitySetupKioskLayoutBinding
import com.afwsamples.testdpc.policy.locktask.viewmodule.KioskViewModule
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import com.base.services.BridgeStatus
import com.base.services.ClawBotLoginStatus
import com.base.services.RemoteChannel
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

open class SetupKioskModeActivity : AppCompatActivity() {
    private var mAdminComponentName: ComponentName? = null

    private var mDevicePolicyManager: DevicePolicyManager? = null
    private var mPackageManager: PackageManager? = null

    private var binding: ActivitySetupKioskLayoutBinding? = null


    private val kioskViewModule: KioskViewModule by viewModel()

    private var mDevicePolicyManagerGateway: DevicePolicyManagerGateway? = null
    private var mUserManager: UserManager? = null

    private var connectivityManager: ConnectivityManager? = null

    private var usbEnableDebugAlertDialog: AlertDialog? = null
    private var permissionGuideDialog: AlertDialog? = null

    private val deviceStatusViewModel: DeviceStatusViewModel by inject()

    private val channelConfig: IRemoteChannelConfigService by inject()
    private val remoteBridgeService: IRemoteBridgeService by inject()

    private var appsActivityClickCount = 0
    private var lastAppsClickTime = 0L


    private companion object {
        const val APPS_CLICK_TIMEOUT = 5000L // 5秒内需要完成5次点击
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mDevicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        mPackageManager = packageManager
        mUserManager = getSystemService(UserManager::class.java)

        binding = ActivitySetupKioskLayoutBinding.inflate(layoutInflater)
        binding?.let { binding ->
            setContentView(binding.root)
            
            // 设置网络按钮点击事件
            binding.setupNetwork.setOnClickListener {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            
            // 修改设备管理员按钮点击事件
            binding.setupDeviceOwner.setOnClickListener {
                if (kioskViewModule.deviceOwnerStateFlow.value) {
                    showRemoveDeviceOwnerDialog()
                } else {
                    showDeviceOwnerInstructions()
                }
            }

            binding.openChatActivity.setOnClickListener {
                openChatActivity()
            }

            binding.openTestActivity.setOnClickListener {
                openTestActivity()
            }

            binding.openAiSettings.setOnClickListener {
                openAiSettings()
            }

            binding.setupRemoteChannel.setOnClickListener {
                openRemoteChannelSettings()
            }

            setupRemoteChannelChips()
        }

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // 监听网络状态
        lifecycleScope.launch {
            connectivityManager?.let { connectivityManager ->
                deviceStatusViewModel.observeNetworkState(connectivityManager).collect { isConnected ->
                    binding?.apply {
                        networkStatus.text = if (isConnected) "已连接" else "未连接"
                        setupNetwork.visibility = if (!isConnected) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        // 如果网络已经链接，设置状态
        if (deviceStatusViewModel.isNetworkConnected(connectivityManager)) {
            binding?.apply {
                networkStatus.text = "已连接"
                setupNetwork.visibility = View.GONE
            }
        }

        // 监听设备管理员状态
        lifecycleScope.launch {
            kioskViewModule.deviceOwnerStateFlow.collect { isDeviceOwner ->
                if (isDeviceOwner) {
                    mAdminComponentName = DeviceAdminReceiver.getComponentName(this@SetupKioskModeActivity)

                    mDevicePolicyManagerGateway =
                        DevicePolicyManagerGatewayImpl(
                            mDevicePolicyManager!!,
                            mUserManager!!,
                            mPackageManager!!,
                            getSystemService(LocationManager::class.java),
                            mAdminComponentName
                        )
                    usbEnableDebugAlertDialog?.dismiss()

                    mDevicePolicyManagerGateway?.setPasswordQuality(0, {}, {})
                    mDevicePolicyManagerGateway?.setKeyguardDisabled(true, {}, {})

                    remoteBridgeService.startEligibleBridges()
                } else {
                    remoteBridgeService.stopTelegramBridge()
                }

                binding?.apply {
                    deviceOwnerStatus.text = if (isDeviceOwner) "已开启" else "未开启"
                    setupDeviceOwner.text = if (isDeviceOwner) "移除设备管理员" else "设置设备管理员"
                    setupDeviceOwner.visibility = View.VISIBLE
                }

            }
        }

        lifecycleScope.launch {
            combine(
                channelConfig.activeRemoteChannel,
                remoteBridgeService.telegramStatus,
                remoteBridgeService.feishuStatus,
                remoteBridgeService.clawBotStatus,
                remoteBridgeService.clawBotLoginStatus
            ) { active, tg, fs, cb, cbLogin ->
                active to kioskStatusLine(active, tg, fs, cb, cbLogin)
            }.collect { (active, line) ->
                binding?.apply {
                    remoteChannelStatus.text = line
                    setupRemoteChannel.visibility = when {
                        needsRemoteSetup(active) -> View.VISIBLE
                        else -> View.GONE
                    }
                }
            }
        }

    }

    private val kioskChipListener =
        com.google.android.material.chip.ChipGroup.OnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@OnCheckedChangeListener
            val ch = kioskChipIdToChannel(checkedId) ?: return@OnCheckedChangeListener
            if (channelConfig.getActiveRemoteChannel() == ch) return@OnCheckedChangeListener
            channelConfig.setActiveRemoteChannel(ch)
        }

    private fun kioskChipIdToChannel(id: Int): RemoteChannel? = when (id) {
        R.id.chip_telegram -> RemoteChannel.TELEGRAM
        R.id.chip_feishu -> RemoteChannel.FEISHU
        R.id.chip_clawbot -> RemoteChannel.CLAWBOT
        R.id.chip_local_server -> RemoteChannel.LOCAL_SERVER
        else -> null
    }

    private fun setupRemoteChannelChips() {
        binding?.chipGroupRemoteChannel?.let { group ->
            group.setOnCheckedChangeListener(null)
            when (channelConfig.getActiveRemoteChannel()) {
                RemoteChannel.TELEGRAM -> group.check(R.id.chip_telegram)
                RemoteChannel.FEISHU -> group.check(R.id.chip_feishu)
                RemoteChannel.CLAWBOT -> group.check(R.id.chip_clawbot)
                RemoteChannel.LOCAL_SERVER -> group.check(R.id.chip_local_server)
            }
            group.setOnCheckedChangeListener(kioskChipListener)
        }
    }

    private fun needsRemoteSetup(active: RemoteChannel): Boolean {
        val tg = remoteBridgeService.telegramStatus.value
        val fs = remoteBridgeService.feishuStatus.value
        val cb = remoteBridgeService.clawBotStatus.value
        val ls = remoteBridgeService.localServerStatus.value
        return when (active) {
            RemoteChannel.TELEGRAM -> tg == BridgeStatus.NOT_CONFIGURED || tg == BridgeStatus.DISCONNECTED
            RemoteChannel.FEISHU -> fs == BridgeStatus.NOT_CONFIGURED || fs == BridgeStatus.DISCONNECTED
            RemoteChannel.CLAWBOT -> cb == BridgeStatus.NOT_CONFIGURED || cb == BridgeStatus.DISCONNECTED
            RemoteChannel.LOCAL_SERVER -> ls == BridgeStatus.NOT_CONFIGURED || ls == BridgeStatus.DISCONNECTED
        }
    }

    private fun kioskStatusLine(
        active: RemoteChannel,
        tg: BridgeStatus,
        fs: BridgeStatus,
        cb: BridgeStatus,
        cbLogin: ClawBotLoginStatus
    ): String = when (active) {
        RemoteChannel.TELEGRAM -> bridgeStatusLabel(tg)
        RemoteChannel.FEISHU -> bridgeStatusLabel(fs)
        RemoteChannel.CLAWBOT -> formatClawBotKioskLine(cb, cbLogin)
        RemoteChannel.LOCAL_SERVER -> bridgeStatusLabel(remoteBridgeService.localServerStatus.value)
    }

    private fun bridgeStatusLabel(status: BridgeStatus): String = when (status) {
        BridgeStatus.NOT_CONFIGURED -> "未配置"
        BridgeStatus.STOPPED -> "已停止"
        BridgeStatus.CONNECTED -> "已连接"
        BridgeStatus.DISCONNECTED -> "未连接"
    }

    private fun formatClawBotKioskLine(bridge: BridgeStatus, login: ClawBotLoginStatus): String {
        val b = bridgeStatusLabel(bridge)
        val l = when (login) {
            ClawBotLoginStatus.NOT_CONFIGURED -> "未配置"
            ClawBotLoginStatus.LOGIN_REQUIRED -> "需登录"
            ClawBotLoginStatus.QR_READY -> "二维码就绪"
            ClawBotLoginStatus.WAITING_CONFIRM -> "待确认"
            ClawBotLoginStatus.CONNECTED -> "已登录"
            ClawBotLoginStatus.DISCONNECTED -> "已断开"
            ClawBotLoginStatus.STOPPED -> "已停止"
        }
        return "桥接: $b · 登录: $l"
    }

    override fun onResume() {
        super.onResume()
        setupRemoteChannelChips()
        checkRequiredPermissions()
    }

    private fun showRemoveDeviceOwnerDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除设备管理员")
            .setMessage("确定要移除设备管理员吗？移除后需要重新设置。")
            .setPositiveButton("确定") { _, _ ->

                AppUtils.showAllHideApps(this)

                mDevicePolicyManagerGateway?.clearDeviceOwnerApp(
                    {
                        Toast.makeText(
                            this,
                            "设备管理员已移除",
                            Toast.LENGTH_SHORT
                        ).show()
                        kioskViewModule.updateDeviceOwnerState(false)
                    },
                    { e: Exception? ->
                        Toast.makeText(
                            this,
                            "移除设备管理员失败: $e",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun showDeviceOwnerInstructions() {
        val componentName = DeviceAdminReceiver.getReceiverComponentName(this).flattenToShortString()
        usbEnableDebugAlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("设置设备管理员")
            .setMessage("请按照以下步骤操作：\n\n" +
                    "1. 打开「设置  >  关于手机」\n" +
                    "2. 连续点击「版本号」7 次开启开发者选项\n" +
                    "3. 在「开发者选项」中开启 USB 调试\n" +
                    "4. 连接电脑，在终端执行以下命令：\n\n" +
                    "adb shell dpm set-device-owner $componentName")
            .setPositiveButton("打开开发者选项") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun checkRequiredPermissions() {
        if (!isAccessibilityServiceEnabled() || !isAccessibilityServiceConnected()) {
            showPermissionGuideDialog(
                title = "需要开启辅助功能",
                message = "Andclaw 需要辅助功能服务来读取屏幕并执行操作，请在设置中找到 Andclaw 并开启。",
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            return
        }

        if (requiresManageAllFilesAccessPermission() && !Environment.isExternalStorageManager()) {
            showPermissionGuideDialog(
                title = "需要文件访问权限",
                message = "Andclaw 需要「所有文件访问」权限来读取下载目录中的 APK 文件并执行静默安装。",
                intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showPermissionGuideDialog(
                title = "需要悬浮窗权限",
                message = "Andclaw 需要「显示在其他应用上层」权限来显示急停悬浮按钮，请在设置中找到 Andclaw 并允许。",
                intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )
            return
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val targetComponent = ComponentName(
            packageName,
            "com.andforce.andclaw.AgentAccessibilityService"
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(targetComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isAccessibilityServiceConnected(): Boolean {
        val targetComponent = ComponentName(
            packageName,
            "com.andforce.andclaw.AgentAccessibilityService"
        ).flattenToString()
        val accessibilityManager =
            getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo?.let { info ->
                ComponentName(info.packageName, info.name).flattenToString()
            } == targetComponent }
    }

    private fun showPermissionGuideDialog(title: String, message: String, intent: Intent) {
        if (permissionGuideDialog?.isShowing == true) {
            return
        }
        permissionGuideDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("去设置") { _, _ -> startActivity(intent) }
            .setNegativeButton("暂时跳过", null)
            .show()
    }

    private fun openAiSettings() {
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    private fun openRemoteChannelSettings() {
        val i = Intent(this, RemoteChannelSettingsActivity::class.java).apply {
            putExtra(
                RemoteChannelSettingsActivity.EXTRA_INITIAL_CHANNEL,
                channelConfig.getActiveRemoteChannel().name
            )
        }
        startActivity(i)
    }

    private fun openChatActivity() {
        val intent = Intent().setClassName(packageName, "com.andforce.andclaw.ChatHistoryActivity")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "启动对话页失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTestActivity() {
        val intent = Intent().setClassName(packageName, "com.andforce.andclaw.ActionTestActivity")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "启动测试页失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

}

internal fun requiresManageAllFilesAccessPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    return sdkInt >= Build.VERSION_CODES.R
}
