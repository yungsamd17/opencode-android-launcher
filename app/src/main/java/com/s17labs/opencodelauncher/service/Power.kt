package com.s17labs.opencodelauncher.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption helpers (Phase 3).
 *
 * Doze kills long-lived processes — the exemption must be requested
 * explicitly (system dialog), never assumed. Callers check [isExempt] to
 * decide whether to show the prompt; [requestExemption] fires it.
 */
object Power {
    fun isExempt(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun requestExemption(activity: Activity) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Device/ROM without the exemption screen — non-fatal.
        }
    }
}
