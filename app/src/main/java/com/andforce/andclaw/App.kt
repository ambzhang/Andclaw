package com.andforce.andclaw

import android.app.admin.DevicePolicyManager
import android.util.Log
import androidx.room.Room
import com.afwsamples.testdpc.KoinApplication
import com.andforce.andclaw.db.AppDatabase
import com.afwsamples.testdpc.policy.locktask.KioskModeHelper
import com.afwsamples.testdpc.policy.locktask.viewmodule.KioskViewModule
import com.andforce.andclaw.service.impl.AppInfoService
import com.andforce.mdm.center.DeviceStatusViewModel
import com.base.services.IAiConfigService
import com.base.services.IAppInfoService
import com.base.services.ITgBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent

class App : KoinApplication() {
    private val appScope = CoroutineScope(Dispatchers.IO)

    private val kioskViewModel: KioskViewModule by lazy {
        KoinJavaComponent.get(KioskViewModule::class.java)
    }

    private val deviceStatusViewModel: DeviceStatusViewModel by lazy {
        KoinJavaComponent.get(DeviceStatusViewModel::class.java)
    }

    companion object {
        const val TAG = "DeviceOwner_App"
    }

    override fun mdmKoinModule(): Module {
        val base = super.mdmKoinModule()
        val appModule = module {
            single { AppInfoService() } bind IAppInfoService::class
            single<ITgBridgeService> { AgentController }
            single<IAiConfigService> { AgentController }
            single { Room.databaseBuilder(get(), AppDatabase::class.java, "andclaw.db").build() }
            single { get<AppDatabase>().chatMessageDao() }
        }
        return module {
            includes(base, appModule)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        val db = KoinJavaComponent.get<AppDatabase>(AppDatabase::class.java)
        AgentController.init(this, db.chatMessageDao())

        // 监听DeviceOwner是否开启
        appScope.launch {
            kioskViewModel.deviceOwnerStateFlow.collect { isDeviceOwner ->
                val content = "是否开：$isDeviceOwner"
                Log.d(TAG, "isDeviceOwner: $isDeviceOwner")

                if (isDeviceOwner) {
                    Log.d(TAG, "设备开启DeviceOwner")
                    val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponentName = DeviceAdminReceiver.getComponentName(this@App)

                    adminComponentName?.let {
                        Log.d(TAG, "用户策略：自动授权权限")
                        devicePolicyManager.setPermissionPolicy(adminComponentName,
                            DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
                    }

                    KioskModeHelper.setDefaultKioskPolicies(isDeviceOwner)
                }
            }
        }

        // 监听锁屏/解锁
        appScope.launch {
            deviceStatusViewModel.observeScreenState(this@App).collect {
            }
        }
    }
}
