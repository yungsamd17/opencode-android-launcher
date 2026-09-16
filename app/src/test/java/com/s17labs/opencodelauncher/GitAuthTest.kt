package com.s17labs.opencodelauncher

import com.s17labs.opencodelauncher.runtime.GitAuth
import com.s17labs.opencodelauncher.runtime.GitSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitAuthTest {

    @Test
    fun ghVersionParsesLongAndShortForms() {
        assertEquals("2.78.0", GitSetup.parseGhVersion("gh version 2.78.0 (2025-11-06)\n"))
        assertEquals("2.1.0", GitSetup.parseGhVersion("2.1.0"))
        assertNull(GitSetup.parseGhVersion("not installed"))
        assertNull(GitSetup.parseGhVersion(""))
    }

    @Test
    fun deviceFlowParsesCodeAndUrl() {
        val out = "First copy your one-time code: AB12-CD34\n" +
            "Open this URL: https://github.com/login/device\n"
        val flow = GitAuth.parseDeviceFlow(out)
        assertEquals("AB12-CD34", flow?.code)
        assertTrue(flow?.url?.contains("github.com/login/device") == true)
    }

    @Test
    fun deviceFlowFallsBackToKnownUrlWhenGhOmitsIt() {
        val flow = GitAuth.parseDeviceFlow("Enter code: ZZ99-YY88")
        assertEquals("ZZ99-YY88", flow?.code)
        assertEquals(GitAuth.FALLBACK_DEVICE_URL, flow?.url)
    }

    @Test
    fun deviceFlowIsNullWithoutACode() {
        assertNull(GitAuth.parseDeviceFlow("Press Enter to open github.com in your browser..."))
        assertNull(GitAuth.parseDeviceFlow(""))
    }

    @Test
    fun statusParsesAccount() {
        val ok = GitAuth.parseStatus(
            0,
            "github.com\n  ✓ Logged in to github.com account octocat (keyring)\n"
        )
        assertTrue(ok.authed)
        assertTrue(ok.summary.contains("octocat"))
    }

    @Test
    fun statusFailsOnNonzeroExit() {
        val bad = GitAuth.parseStatus(1, "You are not logged into any GitHub hosts.")
        assertFalse(bad.authed)
    }

    @Test
    fun loginScriptAcceptsAllDefaults() {
        // Four prompts (host, protocol, git-credential store, method) — one
        // Enter each. If gh adds a prompt, the transcript shows the mismatch.
        assertEquals("\n\n\n\n", GitAuth.LOGIN_STDIN)
    }
}
