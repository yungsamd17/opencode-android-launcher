package com.s17labs.opencodelauncher.storage

import java.io.File

/**
 * Phase 5b: resolves the user's picked project folder into a proot
 * bind-mount (host path → [GUEST_PATH]) so `opencode web` works in real
 * shared-storage files any other app can also read/edit.
 *
 * Pure logic (no Context) so it stays unit-testable on the JVM.
 * Failures are explicit [Mount.Invalid] with a UI-ready reason — the
 * service surfaces them instead of silently falling back to guest home,
 * which would strand the user in the wrong directory.
 */
object ProjectMount {

    /** Stable guest-side path for the bound project folder. */
    const val GUEST_PATH = "/project"

    sealed interface Mount {
        /** No project picked — run in guest home, exactly like Phase 3. */
        data object GuestHome : Mount

        /** Bind [hostDir] into the guest at [GUEST_PATH] and work there. */
        data class Bound(val hostDir: File) : Mount

        /** A project was picked but is unusable — surface [reason], don't guess. */
        data class Invalid(val reason: String) : Mount
    }

    fun resolve(projectDir: File?, hasAccess: Boolean): Mount {
        if (projectDir == null) return Mount.GuestHome
        if (!hasAccess) {
            return Mount.Invalid(
                "Files access was revoked — re-grant it from the Project screen, then start again."
            )
        }
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return Mount.Invalid(
                "Project folder is gone: ${projectDir.absolutePath} — pick it again from the Project screen."
            )
        }
        if (!projectDir.canRead()) {
            return Mount.Invalid(
                "Cannot read project folder: ${projectDir.absolutePath} — check files access, then retry."
            )
        }
        return Mount.Bound(projectDir)
    }

    /** proot `--bind=` entries for [ProotRunner.buildCommand]'s binds map. */
    fun bindsFor(bound: Mount.Bound): Map<String, String> =
        mapOf(bound.hostDir.absolutePath to GUEST_PATH)

    /** Guest shell working directory for a resolved mount. */
    fun guestWorkdir(mount: Mount): String = when (mount) {
        is Mount.Bound -> GUEST_PATH
        else -> "/root"
    }
}
