package com.s17labs.opencodelauncher.runtime

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Installs the Termux-built proot binary + its runtime deps from APK assets
 * into app-private storage and makes them executable.
 *
 * Provenance: official Termux apt repo, SHA-256 verified at fetch time —
 * see scripts/fetch-proot.sh and NOTICES.md. Only the *binaries* are bundled
 * (not their code), as the build plan allows. Re-run the script to refresh.
 *
 * Layout under assets/proot/<android-abi>/ mirrors Termux's $PREFIX slice:
 *   bin/proot, libexec/proot/{loader,loader32}, lib/{libtalloc.so.2,libandroid-shmem.so}
 * The bin/../libexec relationship matters: proot locates its loader relative
 * to its own path. SONAMEs must stay exact (proot DT_NEEDED asks for
 * "libtalloc.so.2" and "libandroid-shmem.so" — assets can't hold symlinks, so
 * the real bytes are stored directly under those names).
 */
object ProotSetup {
    const val PROOT_VERSION = "5.1.107.92"
    const val TALLOC_VERSION = "2.4.3"
    const val SHMEM_VERSION = "0.7"

    fun assetAbiDir(abi: RuntimeBootstrap.Abi): String = when (abi) {
        RuntimeBootstrap.Abi.ARM64 -> "arm64-v8a"
        RuntimeBootstrap.Abi.X86_64 -> "x86_64"
    }

    fun hostDir(filesDir: File): File = File(filesDir, "proot-env/host")
    fun prootBin(filesDir: File): File = File(hostDir(filesDir), "bin/proot")
    fun libDir(filesDir: File): File = File(hostDir(filesDir), "lib")

    private fun versionFile(filesDir: File): File = File(hostDir(filesDir), ".proot-version")

    fun isInstalled(filesDir: File): Boolean {
        return try {
            versionFile(filesDir).takeIf { it.exists() }?.readText()?.trim() == PROOT_VERSION &&
                prootBin(filesDir).canExecute() &&
                File(libDir(filesDir), "libtalloc.so.2").exists() &&
                File(libDir(filesDir), "libandroid-shmem.so").exists()
        } catch (_: Exception) {
            false
        }
    }

    /** Copy + chmod; returns the proot binary on success. */
    fun ensureInstalled(ctx: Context, onLog: (String) -> Unit = {}): Result<File> {
        val abi = RuntimeBootstrap.detectAbi().getOrElse { return Result.failure(it) }
        if (isInstalled(ctx.filesDir)) return Result.success(prootBin(ctx.filesDir))
        return try {
            val dest = hostDir(ctx.filesDir)
            if (dest.exists()) dest.deleteRecursively()
            copyTree(ctx.assets, "proot/${assetAbiDir(abi)}", dest)
            listOf("bin/proot", "libexec/proot/loader", "libexec/proot/loader32").forEach {
                File(dest, it).setExecutable(true)
            }
            versionFile(ctx.filesDir).writeText("$PROOT_VERSION\n")
            val bin = prootBin(ctx.filesDir)
            if (!bin.canExecute()) return Result.failure(IllegalStateException("proot binary not executable after install"))
            onLog("proot $PROOT_VERSION installed (${assetAbiDir(abi)})")
            Result.success(bin)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Env for execing the bundled proot: its DT_NEEDED libs resolve via this path. */
    fun execEnv(filesDir: File): Map<String, String> =
        mapOf("LD_LIBRARY_PATH" to libDir(filesDir).absolutePath)

    private fun copyTree(am: AssetManager, assetPath: String, dest: File) {
        val entries = am.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // Leaf file.
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        } else {
            dest.mkdirs()
            for (e in entries) copyTree(am, "$assetPath/$e", File(dest, e))
        }
    }
}
