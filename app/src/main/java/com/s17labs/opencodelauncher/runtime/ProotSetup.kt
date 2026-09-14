package com.s17labs.opencodelauncher.runtime

import android.content.Context
import java.io.File

/**
 * Locates the Termux-built proot payload shipped in jniLibs and builds the
 * environment needed to run it.
 *
 * Why jniLibs and not assets/ + chmod: apps targeting SDK 29+ cannot exec
 * binaries from writable app-data dirs (SELinux denies with EACCES,
 * error=13). The native library dir IS executable, so proot ships as
 * "native libs" and runs from applicationInfo.nativeLibraryDir. This needs
 * android:extractNativeLibs="true" in the manifest.
 *
 * Layout in jniLibs/<abi>/ (see scripts/fetch-proot.sh for provenance):
 *   libproot.so            — the proot binary (renamed; executed directly)
 *   libproot_loader.so     — guest-side loader, via PROOT_LOADER env
 *   libproot_loader32.so   — 32-bit guest loader, via PROOT_LOADER_32 env
 *   libtalloc.so.2         — exact SONAME from proot's DT_NEEDED
 *   libandroid-shmem.so    — exact SONAME from proot's DT_NEEDED
 *
 * If the installer skips the non-.so name (libtalloc.so.2 — AGP does not
 * package it), the dep is copied from assets/proot-libs/ into app-private
 * storage as a fallback — the *linker* may load it from there (only direct
 * execve of app-data files is blocked, not library mapping).
 */
object ProotSetup {
    const val PROOT_VERSION = "5.1.107.92"

    const val BIN_NAME = "libproot.so"
    const val LOADER_NAME = "libproot_loader.so"
    const val LOADER32_NAME = "libproot_loader32.so"
    const val TALLOC_NAME = "libtalloc.so.2"
    const val SHMEM_NAME = "libandroid-shmem.so"

    fun libAbiDir(abi: RuntimeBootstrap.Abi): String = when (abi) {
        RuntimeBootstrap.Abi.ARM64 -> "arm64-v8a"
        RuntimeBootstrap.Abi.X86_64 -> "x86_64"
    }

    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)
    fun filesLibDir(filesDir: File): File = File(filesDir, "proot-env/lib")
    fun tmpDir(filesDir: File): File = File(filesDir, "proot-env/tmp").apply { mkdirs() }

    data class Resolved(
        val proot: File,
        val env: Map<String, String>,
        val diagnostics: String
    )

    /** Resolve binary + env, installing dep fallbacks as needed. Never throws. */
    fun resolve(ctx: Context, onLog: (String) -> Unit = {}): Result<Resolved> {
        return try {
            val abi = RuntimeBootstrap.detectAbi().getOrElse { return Result.failure(it) }
            val libDir = nativeLibDir(ctx)
            val diag = StringBuilder("nativeLibDir=${libDir.absolutePath} [")
            diag.append(
                (libDir.list()?.sorted()?.joinToString(",") { "$it:${File(libDir, it).length()}" }
                    ?: "<unlistable>")
            )
            diag.append("]")
            onLog(diag.toString())

            val proot = File(libDir, BIN_NAME)
            if (!proot.exists()) {
                return Result.failure(
                    IllegalStateException("proot binary missing from native libs (apk split issue?). $diag")
                )
            }
            if (!proot.canExecute()) {
                return Result.failure(
                    IllegalStateException("proot binary not executable (extractNativeLibs issue?). $diag")
                )
            }

            // Deps must be findable by the linker under their SONAMEs. Prefer
            // the native dir; fall back to app-private copies from assets
            // if the installer skipped them.
            val searchDirs = mutableListOf(libDir.absolutePath)
            val fallbackDir = filesLibDir(ctx.filesDir)
            for (dep in listOf(TALLOC_NAME, SHMEM_NAME)) {
                if (!File(libDir, dep).exists()) {
                    onLog("$dep missing from native libs — copying fallback from assets…")
                    val fb = copyAssetDep(ctx, "proot-libs/${libAbiDir(abi)}/$dep", File(fallbackDir, dep))
                    if (fb != null) {
                        onLog("fallback $dep ready (${fb.length()}B)")
                    } else {
                        onLog("WARNING: $dep unavailable anywhere; proot may fail to start")
                    }
                }
            }
            if (fallbackDir.exists()) searchDirs += fallbackDir.absolutePath

            val loader = File(libDir, LOADER_NAME).takeIf { it.exists() }
                ?: return Result.failure(IllegalStateException("proot loader missing from native libs. $diag"))
            val loader32 = File(libDir, LOADER32_NAME).takeIf { it.exists() }
                ?: return Result.failure(IllegalStateException("proot loader32 missing from native libs. $diag"))

            // proot's compiled-in tmp default (/data/data/com.termux/...) is not
            // ours — point it at our private tmp.
            val env = mapOf(
                "LD_LIBRARY_PATH" to searchDirs.joinToString(":"),
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_LOADER_32" to loader32.absolutePath,
                "PROOT_TMP_DIR" to tmpDir(ctx.filesDir).absolutePath
            )
            onLog("proot $PROOT_VERSION resolved (${libAbiDir(abi)})")
            Result.success(Resolved(proot, env, diag.toString()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyAssetDep(ctx: Context, assetPath: String, dest: File): File? {
        return try {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }
}
