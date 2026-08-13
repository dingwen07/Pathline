package net.extrawdw.apps.locationhistory.data.places

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcloudSetupCommandTest {
    private val signers = listOf(
        "0123456789ABCDEF0123456789ABCDEF01234567",
        "89ABCDEF0123456789ABCDEF0123456789ABCDEF",
    )

    @Test
    fun posixCommand_isOneLineAndUsesPosixControlOperators() {
        val command = command(GcloudCommandShell.POSIX)

        assertTrue(command.contains(" && "))
        assertTrue(command.contains("/dev/null"))
        assertFalse(command.contains("\$LASTEXITCODE"))
        assertOneLineWithRestrictions(command)
    }

    @Test
    fun powershellCommand_isOneLineAndUsesPowerShellControlOperators() {
        val command = command(GcloudCommandShell.POWERSHELL)

        assertTrue(command.contains("if (\$LASTEXITCODE -eq 0)"))
        assertTrue(command.contains("*> \$null"))
        assertFalse(command.contains("/dev/null"))
        assertFalse(command.contains(" && "))
        assertOneLineWithRestrictions(command)
    }

    private fun command(shell: GcloudCommandShell): String = buildGcloudSetupCommand(
        project = "pathline-test-project",
        packageName = "net.extrawdw.apps.locationhistory",
        signers = signers,
        keyId = "pathline-android",
        shell = shell,
    )

    private fun assertOneLineWithRestrictions(command: String) {
        assertFalse(command.contains('\n'))
        assertFalse(command.contains('\r'))
        signers.forEach { signer -> assertTrue(command.contains(signer)) }
        assertTrue(command.contains("places.googleapis.com"))
        assertTrue(command.contains("routes.googleapis.com"))
    }
}
