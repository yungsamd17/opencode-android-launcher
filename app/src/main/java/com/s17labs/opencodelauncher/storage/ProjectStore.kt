package com.s17labs.opencodelauncher.storage

import android.content.Context
import java.io.File

/**
 * Remembers the user's project folder on shared storage (Phase 5).
 * Stored as a plain path string — only valid while the dir still exists;
 * [get] returns null otherwise so callers fall back to the guest home.
 */
object ProjectStore {
    private const val PREFS = "opencode_launcher"
    private const val KEY_PROJECT_PATH = "project_host_path"

    fun get(ctx: Context): File? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_PATH, null) ?: return null
        return File(raw).takeIf { it.exists() && it.isDirectory }
    }

    fun set(ctx: Context, dir: File) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_PATH, dir.absolutePath)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PROJECT_PATH)
            .apply()
    }
}
