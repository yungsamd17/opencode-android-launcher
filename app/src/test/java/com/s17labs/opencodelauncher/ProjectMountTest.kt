package com.s17labs.opencodelauncher

import com.s17labs.opencodelauncher.runtime.ProotRunner
import com.s17labs.opencodelauncher.storage.ProjectMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectMountTest {

    @Test
    fun nullProjectRunsInGuestHome() {
        val mount = ProjectMount.resolve(null, hasAccess = true)
        assertEquals(ProjectMount.Mount.GuestHome, mount)
        assertEquals("/root", ProjectMount.guestWorkdir(mount))
    }

    @Test
    fun missingFolderIsInvalidNotSilentFallback() {
        val gone = File(createTempDir("proj"), "deleted")
        val mount = ProjectMount.resolve(gone, hasAccess = true)
        assertTrue(mount is ProjectMount.Mount.Invalid)
        assertTrue((mount as ProjectMount.Mount.Invalid).reason.contains(gone.absolutePath))
    }

    @Test
    fun fileIsInvalid() {
        val file = File.createTempFile("proj", ".tmp")
        try {
            val mount = ProjectMount.resolve(file, hasAccess = true)
            assertTrue(mount is ProjectMount.Mount.Invalid)
        } finally {
            file.delete()
        }
    }

    @Test
    fun revokedAccessIsInvalid() {
        val dir = createTempDir("proj")
        try {
            val mount = ProjectMount.resolve(dir, hasAccess = false)
            assertTrue(mount is ProjectMount.Mount.Invalid)
            assertTrue((mount as ProjectMount.Mount.Invalid).reason.contains("re-grant"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validFolderBindsToGuestProject() {
        val dir = createTempDir("proj")
        try {
            val mount = ProjectMount.resolve(dir, hasAccess = true)
            assertTrue(mount is ProjectMount.Mount.Bound)
            val bound = mount as ProjectMount.Mount.Bound
            assertEquals(mapOf(dir.absolutePath to "/project"), ProjectMount.bindsFor(bound))
            assertEquals("/project", ProjectMount.guestWorkdir(mount))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun bindCommandCarriesProjectMount() {
        val dir = createTempDir("proj")
        try {
            val bound = ProjectMount.Mount.Bound(dir)
            val cmd = ProotRunner.buildCommand(
                File("/x/bin/proot"),
                File("/x/rootfs"),
                binds = ProjectMount.bindsFor(bound)
            )
            assertTrue(cmd.contains("--bind=${dir.absolutePath}:/project"))
            assertTrue(cmd.contains("--bind=/proc"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
