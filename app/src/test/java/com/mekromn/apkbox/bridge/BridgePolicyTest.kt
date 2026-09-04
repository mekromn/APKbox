package com.mekromn.apkbox.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {
    private val now = 1_788_336_000_000L
    private val trustedUntil = now + 10 * 60_000L

    @Test
    fun structuredDiagnosticsAreReadOnly() {
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request(BridgeCommandType.LOGCAT)))
        assertEquals(
            BridgeRisk.READ_ONLY,
            BridgePolicy.classify(request(BridgeCommandType.APP_LOGCAT, packageName = "com.example.app")),
        )
        assertEquals(
            BridgeRisk.READ_ONLY,
            BridgePolicy.classify(request(BridgeCommandType.DUMPSYS, service = "package")),
        )
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request(BridgeCommandType.UI_SNAPSHOT)))
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request(BridgeCommandType.SCREENSHOT)))
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request(BridgeCommandType.AGENT_STATUS, runId = "run-1")))
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request(BridgeCommandType.BUILD_STATUS, runId = "build-run-1")))
    }

    @Test
    fun universalInventoryAndApkRetrievalAreReadOnly() {
        val types = listOf(
            BridgeCommandType.JOB_LIST,
            BridgeCommandType.JOB_STATUS,
            BridgeCommandType.PROJECT_LIST,
            BridgeCommandType.PROJECT_GET,
            BridgeCommandType.APK_LIST,
            BridgeCommandType.APK_SEARCH,
            BridgeCommandType.APK_INSPECT,
            BridgeCommandType.APK_PULL,
            BridgeCommandType.PACKAGE_STATE,
            BridgeCommandType.INSTALLED_APPS,
            BridgeCommandType.DEVICE_STATE,
        )
        types.forEach { type -> assertEquals("$type must stay read-only", BridgeRisk.READ_ONLY, BridgePolicy.classify(request(type))) }
    }

    @Test
    fun jobCancelIsScopedDebugButResumeIsAlwaysMutating() {
        val cancel = request(BridgeCommandType.JOB_CANCEL, jobId = "job-42")
        assertEquals(BridgeRisk.DEBUG_ACTION, BridgePolicy.classify(cancel))
        assertTrue(BridgePolicy.trustedSessionEligible(cancel))
        assertTrue(BridgePolicy.mayAutoExecute(cancel, trustedUntil, true, true, now))

        val resume = request(BridgeCommandType.JOB_RESUME, jobId = "job-42")
        assertEquals(BridgeRisk.MUTATING, BridgePolicy.classify(resume))
        assertFalse(BridgePolicy.trustedSessionEligible(resume))
        assertFalse(BridgePolicy.mayAutoExecute(resume, trustedUntil, true, true, now))
    }

    @Test
    fun obviousReadOnlyShellMayUseTrustedSession() {
        val request = request(BridgeCommandType.SHELL, command = "getprop ro.product.model")
        assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request))
        assertTrue(
            BridgePolicy.mayAutoExecute(
                request,
                trustedUntil,
                allowInformational = false,
                allowPopups = false,
                now = now,
            )
        )
    }

    @Test
    fun mutationNeverAutoExecutesEvenInsideTrustedSession() {
        val dangerous = listOf(
            "pm clear com.example.app",
            "settings put global airplane_mode_on 1",
            "rm -rf /data/local/tmp/test",
            "am force-stop com.example.app",
            "reboot",
        )
        dangerous.forEach { command ->
            val request = request(BridgeCommandType.SHELL, command = command)
            assertEquals("$command must be mutating", BridgeRisk.MUTATING, BridgePolicy.classify(request))
            assertFalse(
                "$command must never auto execute",
                BridgePolicy.mayAutoExecute(
                    request,
                    trustedUntil,
                    allowInformational = true,
                    allowPopups = true,
                    now = now,
                )
            )
            assertFalse(BridgePolicy.trustedSessionEligible(request))
        }
    }

    @Test
    fun buildStartIsAlwaysMutatingAndNeedsFreshApproval() {
        val start = request(BridgeCommandType.BUILD_START, runId = "build-run-1", buildId = "candidate-1")
        assertEquals(BridgeRisk.MUTATING, BridgePolicy.classify(start))
        assertFalse(BridgePolicy.mayAutoExecute(start, trustedUntil, true, true, now))
        assertFalse(BridgePolicy.trustedSessionEligible(start))
    }

    @Test
    fun remoteApkUrlInstallIsAlwaysMutatingAndNeedsFreshApproval() {
        val install = request(BridgeCommandType.APK_INSTALL_URL)
        assertEquals(BridgeRisk.MUTATING, BridgePolicy.classify(install))
        assertFalse(BridgePolicy.mayAutoExecute(install, trustedUntil, true, true, now))
        assertFalse(BridgePolicy.trustedSessionEligible(install))
    }

    @Test
    fun autonomousStartAndResumeNeverInheritTrustedSession() {
        listOf(BridgeCommandType.AGENT_START, BridgeCommandType.AGENT_RESUME).forEach { type ->
            val request = request(type, packageName = "com.example.app", runId = "camera-run-42")
            assertEquals(BridgeRisk.DEBUG_ACTION, BridgePolicy.classify(request))
            assertFalse(BridgePolicy.mayAutoExecute(request, trustedUntil, true, true, now))
            assertFalse(BridgePolicy.trustedSessionEligible(request))
        }
    }

    @Test
    fun shellCompositionAndUnknownCommandsRequireFreshApproval() {
        val commands = listOf(
            "getprop ro.product.model; reboot",
            "logcat -d | grep AndroidRuntime",
            "echo test",
            "sh -c id",
            "am start -n com.example.app/.MainActivity",
            "am broadcast -a com.example.DO_THING",
        )
        commands.forEach { command ->
            val request = request(BridgeCommandType.SHELL, command = command)
            assertEquals("$command must remain dangerous", BridgeRisk.DANGEROUS, BridgePolicy.classify(request))
            assertFalse(
                BridgePolicy.mayAutoExecute(
                    request,
                    trustedUntil,
                    allowInformational = true,
                    allowPopups = true,
                    now = now,
                )
            )
        }
    }

    @Test
    fun trustedSessionExpiresImmediatelyAtBoundary() {
        val request = request(BridgeCommandType.LOGCAT)
        assertTrue(BridgePolicy.mayAutoExecute(request, now + 1, false, false, now))
        assertFalse(BridgePolicy.mayAutoExecute(request, now, false, false, now))
    }

    @Test
    fun informationalMessagesRespectIndependentPopupToggle() {
        val nonBlocking = listOf(
            BridgeCommandType.TOAST,
            BridgeCommandType.NOTIFICATION,
            BridgeCommandType.MESSAGE_HEADS_UP,
        )
        val intrusive = listOf(
            BridgeCommandType.POPUP,
            BridgeCommandType.MESSAGE_SMALL_POPUP,
            BridgeCommandType.MESSAGE_ALWAYS_ON_TOP,
            BridgeCommandType.MESSAGE_FULL_WINDOW,
            BridgeCommandType.PICTURE_MESSAGE,
        )

        (nonBlocking + intrusive).forEach { type ->
            assertEquals(BridgeRisk.INFO, BridgePolicy.classify(request(type, message = "test")))
            assertFalse(BridgePolicy.mayAutoExecute(request(type, message = "test"), 0L, false, false, now))
        }

        nonBlocking.forEach { type ->
            assertTrue(
                "$type should only require informational permission",
                BridgePolicy.mayAutoExecute(request(type, message = "test"), 0L, true, false, now),
            )
        }
        intrusive.forEach { type ->
            assertFalse(
                "$type should remain blocked when popup permission is off",
                BridgePolicy.mayAutoExecute(request(type, message = "test"), 0L, true, false, now),
            )
            assertTrue(
                "$type should run when both informational and popup permissions are enabled",
                BridgePolicy.mayAutoExecute(request(type, message = "test"), 0L, true, true, now),
            )
        }
    }

    @Test
    fun launchIsDebugActionButStillNeedsTrust() {
        val launch = request(BridgeCommandType.LAUNCH, packageName = "com.example.app")
        assertEquals(BridgeRisk.DEBUG_ACTION, BridgePolicy.classify(launch))
        assertFalse(BridgePolicy.mayAutoExecute(launch, 0L, true, true, now))
        assertTrue(BridgePolicy.mayAutoExecute(launch, trustedUntil, true, true, now))
    }

    @Test
    fun trustedUiAutomationRequiresPackageRunAndSequenceScope() {
        val unscoped = request(BridgeCommandType.UI_FIND_TAP, packageName = "com.example.app")
        assertEquals(BridgeRisk.DEBUG_ACTION, BridgePolicy.classify(unscoped))
        assertFalse(BridgePolicy.mayAutoExecute(unscoped, trustedUntil, true, true, now))
        assertFalse(BridgePolicy.trustedSessionEligible(unscoped))

        val scoped = request(
            BridgeCommandType.UI_FIND_TAP,
            packageName = "com.example.app",
            runId = "camera-regression-42",
            sequenceNumber = 7,
        )
        assertTrue(BridgePolicy.mayAutoExecute(scoped, trustedUntil, true, true, now))
        assertTrue(BridgePolicy.trustedSessionEligible(scoped))

        val badPackage = scoped.copy(packageName = "not-a-package")
        assertFalse(BridgePolicy.mayAutoExecute(badPackage, trustedUntil, true, true, now))

        val staleSequenceShape = scoped.copy(sequenceNumber = 0)
        assertFalse(BridgePolicy.mayAutoExecute(staleSequenceShape, trustedUntil, true, true, now))
    }

    private fun request(
        type: BridgeCommandType,
        command: String = "",
        packageName: String = "",
        service: String = "",
        message: String = "",
        runId: String = "",
        buildId: String = "",
        jobId: String = "",
        sequenceNumber: Long = 0L,
    ) = BridgeRequest(
        id = "test-request",
        type = type,
        command = command,
        packageName = packageName,
        service = service,
        message = message,
        runId = runId,
        buildId = buildId,
        jobId = jobId,
        sequenceNumber = sequenceNumber,
        createdAtEpochMs = now,
        expiresAtEpochMs = trustedUntil,
    )
}
