package com.s17labs.opencodelauncher

import com.s17labs.opencodelauncher.runtime.AlpineCatalog
import com.s17labs.opencodelauncher.runtime.GuestSetup
import com.s17labs.opencodelauncher.runtime.ProotRunner
import com.s17labs.opencodelauncher.runtime.ProotSetup
import com.s17labs.opencodelauncher.runtime.RuntimeBootstrap
import com.s17labs.opencodelauncher.runtime.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapLogicTest {

    @Test
    fun abiPrefersArm64() {
        val res = RuntimeBootstrap.mapAbis(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(RuntimeBootstrap.Abi.ARM64, res.getOrThrow())
    }

    @Test
    fun abiFallsBackToX86_64() {
        val res = RuntimeBootstrap.mapAbis(listOf("x86_64", "x86"))
        assertEquals(RuntimeBootstrap.Abi.X86_64, res.getOrThrow())
    }

    @Test
    fun abiFailsClearlyWhenUnsupported() {
        val res = RuntimeBootstrap.mapAbis(listOf("armeabi-v7a"))
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("Unsupported ABI"))
    }

    @Test
    fun alpineUrlsFollowCdnPattern() {
        val url = AlpineCatalog.tarballUrl(RuntimeBootstrap.Abi.ARM64)
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.3-aarch64.tar.gz",
            url
        )
        val x86 = AlpineCatalog.tarballUrl(RuntimeBootstrap.Abi.X86_64)
        assertTrue(x86.endsWith("alpine-minirootfs-3.23.3-x86_64.tar.gz"))
        assertEquals("$url.sha256", AlpineCatalog.sha256Url(RuntimeBootstrap.Abi.ARM64))
    }

    @Test
    fun shaSidecarParsesBothForms() {
        assertEquals(
            "a".repeat(64),
            Sha256.parseSidecar("a".repeat(64) + "  alpine-minirootfs-3.23.3-aarch64.tar.gz")
        )
        assertEquals("b".repeat(64), Sha256.parseSidecar("B".repeat(64) + "\n"))
        assertNull(Sha256.parseSidecar("not-a-hash"))
    }

    @Test
    fun prootAssetDirsMatchAndroidAbis() {
        assertEquals("arm64-v8a", ProotSetup.libAbiDir(RuntimeBootstrap.Abi.ARM64))
        assertEquals("x86_64", ProotSetup.libAbiDir(RuntimeBootstrap.Abi.X86_64))
    }

    @Test
    fun prootCommandBindsSystemDirs() {
        val cmd = ProotRunner.buildCommand(
            java.io.File("/x/bin/proot"),
            java.io.File("/x/rootfs")
        )
        assertTrue(cmd.contains("--rootfs=/x/rootfs"))
        assertTrue(cmd.contains("--bind=/proc"))
        assertTrue(cmd.contains("--bind=/dev"))
        assertTrue(cmd.takeLast(3) == listOf("/bin/sh", "-c", "echo proot-ok"))
    }

    @Test
    fun versionLinesParse() {
        assertEquals("v22.14.0", GuestSetup.parseVersionLine("v22.14.0\n"))
        assertEquals("10.8.2", GuestSetup.parseVersionLine("10.8.2"))
        assertEquals("1.2.3", GuestSetup.parseVersionLine("1.2.3 (release)"))
        assertNull(GuestSetup.parseVersionLine("not a version"))
        assertNull(GuestSetup.parseVersionLine(""))
    }

    @Test
    fun guestShellAnchorsOnBusyboxFile() {
        // Regression: Alpine's bin/sh is an ABSOLUTE symlink (sh -> /bin/busybox),
        // which never host-resolves. A rootfs with marker + busybox but a
        // dangling bin/sh must count as bootstrapped.
        val dir = createTempDir("rootfs")
        try {
            val bin = java.io.File(dir, "bin").apply { mkdirs() }
            java.io.File(bin, "busybox").writeText("fake")
            assertTrue(RuntimeBootstrap.hasGuestShell(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
