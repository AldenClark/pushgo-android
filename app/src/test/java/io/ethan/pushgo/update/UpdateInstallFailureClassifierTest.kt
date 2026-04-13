package io.ethan.pushgo.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallFailureClassifierTest {
    @Test
    fun `security exception is treated as permission denied only when source not allowed`() {
        val denied = UpdateInstallFailureClassifier.isPermissionDenied(
            error = SecurityException("install blocked by policy"),
            canRequestPackageInstalls = false,
        )
        val allowed = UpdateInstallFailureClassifier.isPermissionDenied(
            error = SecurityException("install blocked by policy"),
            canRequestPackageInstalls = true,
        )
        assertTrue(denied)
        assertFalse(allowed)
    }

    @Test
    fun `permission-like message is recognized case-insensitively`() {
        val blocked = UpdateInstallFailureClassifier.isPermissionDenied(
            error = IllegalStateException("Permission denied: REQUEST_INSTALL_PACKAGES"),
            canRequestPackageInstalls = false,
        )
        assertTrue(blocked)
    }

    @Test
    fun `unknown sources hint is recognized when package installs are denied`() {
        val blocked = UpdateInstallFailureClassifier.isPermissionDenied(
            error = RuntimeException("Not allowed to install from Unknown Sources"),
            canRequestPackageInstalls = false,
        )
        assertTrue(blocked)
    }

    @Test
    fun `permission-like failure is ignored when source already allowed`() {
        val blocked = UpdateInstallFailureClassifier.isPermissionDenied(
            error = RuntimeException("request_install_packages failed"),
            canRequestPackageInstalls = true,
        )
        assertFalse(blocked)
    }

    @Test
    fun `non permission failure is not misclassified`() {
        val blocked = UpdateInstallFailureClassifier.isPermissionDenied(
            error = IllegalStateException("Checksum mismatch"),
            canRequestPackageInstalls = false,
        )
        assertFalse(blocked)
    }
}
