package com.s17labs.opencodelauncher.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Shared-storage access (Phase 5).
 *
 * SAF folder pickers hand out content:// URIs, which a native process
 * (proot/opencode) cannot bind-mount or operate on. All-files access
 * (MANAGE_EXTERNAL_STORAGE) gives real paths like
 * /storage/emulated/0/Projects/myrepo that CAN be bind-mounted into the
 * rootfs — same reason AndCode requests it. Trade-off: extra Play Store
 * scrutiny, so we ship direct APK / F-Droid first (see plan Phase 5).
 */
object StorageAccess {
    fun hasFullAccess(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Exception) {
                false
            }
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Explicit system screen — never requested silently. */
    fun requestIntent(ctx: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            )
        }
    }

    fun sharedRoot(): java.io.File =
        Environment.getExternalStorageDirectory() ?: java.io.File("/storage/emulated/0")
}
