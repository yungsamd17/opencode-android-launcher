package com.s17labs.opencodelauncher.runtime

/**
 * Alpine rootfs catalog.
 *
 * URL pattern derived from proot-distro's Alpine build recipe
 * (termux/proot-distro, build-recipes/alpine.yaml + distro-build/alpine.sh):
 *   https://dl-cdn.alpinelinux.org/alpine/v<major.minor>/releases/<arch>/alpine-minirootfs-<version>-<arch>.tar.gz
 * with a matching .sha256 sidecar at the same CDN path.
 *
 * Pinned to Alpine 3.23.3 (proot-distro recipe version). Bump deliberately,
 * not opportunistically — every bump needs a fresh on-device bootstrap test.
 *
 * This file contains only the URL pattern + version (facts, not copied code),
 * so it is original glue. The download/verify/extract flow it drives is
 * inspired by TermuxInstaller.java + proot-distro.sh — see NOTICES.md.
 */
object AlpineCatalog {
    const val VERSION = "3.23.3"

    fun minor(): String = VERSION.split(".").take(2).joinToString(".")

    fun tarballUrl(abi: RuntimeBootstrap.Abi): String {
        val arch = abi.rootfsArch
        return "https://dl-cdn.alpinelinux.org/alpine/v${minor()}/releases/$arch/alpine-minirootfs-$VERSION-$arch.tar.gz"
    }

    fun sha256Url(abi: RuntimeBootstrap.Abi): String = tarballUrl(abi) + ".sha256"

    fun tarballFileName(abi: RuntimeBootstrap.Abi): String {
        val arch = abi.rootfsArch
        return "alpine-minirootfs-$VERSION-$arch.tar.gz"
    }
}
