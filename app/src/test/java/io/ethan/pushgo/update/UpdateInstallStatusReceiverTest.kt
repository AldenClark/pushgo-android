package io.ethan.pushgo.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallStatusReceiverTest {
    @Test
    fun `blocked status is recoverable`() {
        assertTrue(
            UpdateInstallStatusReceiver.isRecoverableInstallerBlock(
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                "INSTALL_FAILED_USER_RESTRICTED",
            )
        )
    }

    @Test
    fun `aborted status is recoverable`() {
        assertTrue(
            UpdateInstallStatusReceiver.isRecoverableInstallerBlock(
                PackageInstaller.STATUS_FAILURE_ABORTED,
                "INSTALL_FAILED_ABORTED: User rejected permissions",
            )
        )
    }

    @Test
    fun `hinted message is recoverable even on generic failure`() {
        assertTrue(
            UpdateInstallStatusReceiver.isRecoverableInstallerBlock(
                PackageInstaller.STATUS_FAILURE,
                "blocked session install because sdk version too low",
            )
        )
    }

    @Test
    fun `unrelated failure is not treated as recoverable block`() {
        assertFalse(
            UpdateInstallStatusReceiver.isRecoverableInstallerBlock(
                PackageInstaller.STATUS_FAILURE_INVALID,
                "INSTALL_FAILED_VERSION_DOWNGRADE",
            )
        )
    }
}

