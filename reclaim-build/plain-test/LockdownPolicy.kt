package com.kostas.reclaim

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class ReclaimDeviceAdminReceiver : DeviceAdminReceiver()

object LockdownPolicy {
    fun adminComponent(context: Context) = ComponentName(context, ReclaimDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm?.isAdminActive(adminComponent(context)) == true
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm?.isDeviceOwnerApp(context.packageName) == true
    }

    fun requestAdmin(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Reclaim uses device-admin protection during Locked Mode.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun engage(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            runCatching { dpm.setUninstallBlocked(adminComponent(context), context.packageName, true) }
        }
    }

    fun disengage(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            runCatching { dpm.setUninstallBlocked(adminComponent(context), context.packageName, false) }
        }
    }
}
