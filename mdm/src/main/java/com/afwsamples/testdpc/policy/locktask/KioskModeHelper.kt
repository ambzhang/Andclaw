package com.afwsamples.testdpc.policy.locktask

import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings.Global
import android.util.Log
import androidx.appcompat.app.AppCompatActivity.DEVICE_POLICY_SERVICE
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import com.andforce.andclaw.DeviceAdminReceiver
import com.afwsamples.testdpc.common.Util
import com.andforce.mdm.ext.getAppContext
import com.base.services.IAppInfoService
import org.koin.java.KoinJavaComponent

object KioskModeHelper {
    private const val TAG = "KioskModeHelper"

    private const val KIOSK_PREFERENCE_FILE = "kiosk_preference_file"

    private val appInfoService: IAppInfoService by lazy {
        KoinJavaComponent.get(IAppInfoService::class.java)
    }

    private val KIOSK_USER_RESTRICTIONS = emptyList<String>()
//        arrayOf(
//        UserManager.DISALLOW_SAFE_BOOT,
//        UserManager.DISALLOW_FACTORY_RESET,
//        UserManager.DISALLOW_ADD_USER,
//        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
//        UserManager.DISALLOW_DEBUGGING_FEATURES
//    )

    private fun setUserRestriction(restriction: String, disallow: Boolean) {
        val devicePolicyManager = this.getAppContext().getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponentName = DeviceAdminReceiver.getComponentName(this.getAppContext())
        adminComponentName?.let {
            if (disallow) {
                devicePolicyManager.addUserRestriction(it, restriction)
            } else {
                devicePolicyManager.clearUserRestriction(it, restriction)
            }
        }
    }

    fun setDefaultKioskPolicies(active: Boolean) {
        val devicePolicyManager = this.getAppContext().getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        // restore or save previous configuration
        if (active) {
            saveCurrentConfiguration()
            // setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, active)
            // setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, active)
            // setUserRestriction(UserManager.DISALLOW_ADD_USER, active)
            // setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, active)

            // Log.d(TAG, "setDefaultKioskPolicies: isDebug = ${appInfoService.isDebug()}")

            // // 如果是 release 版本，禁用调试功能
            // if (!appInfoService.isDebug()) {
            //     Log.d(TAG, "setDefaultKioskPolicies: DISALLOW_DEBUGGING_FEATURES")
            //     setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, active)
            //     // 禁用 USB 调试
            //     Log.d(TAG, "setDefaultKioskPolicies: set ADB_ENABLED to 0")
            //     val adminComponentName = DeviceAdminReceiver.getComponentName(this.getAppContext())
            //     adminComponentName?.let {
            //         devicePolicyManager.setGlobalSetting(adminComponentName, Global.ADB_ENABLED, "0")
            //         devicePolicyManager.setGlobalSetting(
            //             adminComponentName,
            //             Global.DEVELOPMENT_SETTINGS_ENABLED,
            //             "0"
            //         )
            //     }
            // }

        } else {
            Log.d(TAG, "setDefaultKioskPolicies: restorePreviousConfiguration()")
            restorePreviousConfiguration()
        }
    }

    private fun saveCurrentConfiguration() {
        val mDevicePolicyManager = this.getAppContext().getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val mAdminComponentName = DeviceAdminReceiver.getComponentName(this.getAppContext())

        if (Util.SDK_INT >= Build.VERSION_CODES.N) {
            val settingsBundle = mDevicePolicyManager?.getUserRestrictions(
                mAdminComponentName!!
            )
            val editor =
                this.getAppContext().getSharedPreferences(KIOSK_PREFERENCE_FILE, MODE_PRIVATE).edit()

            for (userRestriction in KIOSK_USER_RESTRICTIONS) {
                val currentSettingValue = settingsBundle?.getBoolean(userRestriction)
                currentSettingValue?.let {
                    editor.putBoolean(userRestriction, currentSettingValue)
                }
            }
            editor.apply()
        }
    }

    private fun restorePreviousConfiguration() {
        if (Util.SDK_INT >= Build.VERSION_CODES.N) {
            val sharedPreferences =
                this.getAppContext().getSharedPreferences(KIOSK_PREFERENCE_FILE, MODE_PRIVATE)

            for (userRestriction in KIOSK_USER_RESTRICTIONS) {
                val prevSettingValue = sharedPreferences.getBoolean(userRestriction, false)
                setUserRestriction(userRestriction, prevSettingValue)
            }
        }
    }
}