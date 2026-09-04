package com.mekromn.apkbox.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeModelsTest {
    @Test
    fun requestRoundTripPreservesDebugIntent() {
        val original = BridgeRequest(
            id = "camera-logcat-001",
            type = BridgeCommandType.APP_LOGCAT,
            packageName = "com.example.camera",
            reason = "Capture crash after launch",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
            timeoutSeconds = 33,
            source = "ChatGPT via Continuity",
        )
        val parsed = BridgeRequest.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    fun advancedRequestRoundTripPreservesRunAndBuildIdentity() {
        val original = BridgeRequest(
            id = "build-start-001",
            type = BridgeCommandType.BUILD_START,
            packageName = "com.example.camera",
            runId = "build-run-42",
            buildId = "candidate-42",
            reason = "Install exact candidate and launch it",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
        )
        assertEquals(original, BridgeRequest.fromJson(original.toJson()))
    }

    @Test
    fun pictureMessageRoundTripPreservesPrivateImagePath() {
        val original = BridgeRequest(
            id = "picture-001",
            type = BridgeCommandType.PICTURE_MESSAGE,
            title = "Look here",
            message = "This image shows the UI element I mean.",
            imagePath = "bridge/devices/apkbox-pixel-test/message-assets/picture-001.png",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
        )
        assertEquals(original, BridgeRequest.fromJson(original.toJson()))
    }

    @Test
    fun remoteApkInstallRoundTripPreservesArchiveAndInstallOptions() {
        val original = BridgeRequest(
            id = "install-url-001",
            type = BridgeCommandType.APK_INSTALL_URL,
            downloadUrl = "https://github.com/example/app/releases/download/test/app.apk",
            expectedApkSha256 = "a".repeat(64),
            packageName = "com.example.app",
            saveToProject = true,
            projectId = "project-42",
            projectName = "Example App",
            displayName = "Example-test.apk",
            archiveTitle = "Camera fix candidate",
            archiveDescription = "Fixes black preview after the 5x lens switch.",
            requiresBuildToken = true,
            allowDowngrade = true,
            autoLaunch = true,
            reason = "Install the candidate for device validation",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
        )
        assertEquals(original, BridgeRequest.fromJson(original.toJson()))
    }

    @Test
    fun universalJobInventoryAndApkRetrievalFieldsRoundTrip() {
        val original = BridgeRequest(
            id = "apk-pull-001",
            type = BridgeCommandType.APK_PULL,
            jobId = "pull-job-42",
            apkRecordId = "record-42",
            projectId = "project-42",
            packageName = "com.example.app",
            query = "candidate",
            limit = 321,
            includeSystemApps = true,
            reason = "Pull exact stored APK",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
        )
        assertEquals(original, BridgeRequest.fromJson(original.toJson()))
    }

    @Test
    fun jobAndExactApkCommandsRequireStableIds() {
        listOf("JOB_STATUS", "JOB_CANCEL", "JOB_RESUME").forEach { type ->
            val json = JSONObject().put("id", "job-required-$type").put("type", type)
            assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(json) }
        }
        listOf("APK_INSPECT", "APK_PULL").forEach { type ->
            val json = JSONObject().put("id", "record-required-$type").put("type", type)
            assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(json) }
        }
    }

    @Test
    fun invalidAdvancedIdsAndImageTraversalAreRejectedDuringParsing() {
        val badRun = JSONObject()
            .put("id", "valid-id")
            .put("type", "AGENT_STATUS")
            .put("runId", "../bad")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(badRun) }

        val badBuild = JSONObject()
            .put("id", "valid-id")
            .put("type", "BUILD_START")
            .put("buildId", "candidate/../../bad")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(badBuild) }

        val badImage = JSONObject()
            .put("id", "picture-002")
            .put("type", "PICTURE_MESSAGE")
            .put("imagePath", "bridge/devices/device/message-assets/../../secret.txt")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(badImage) }
    }

    @Test
    fun remoteApkInstallRequiresHttpsUrlAndValidOptionalSha() {
        val noUrl = JSONObject()
            .put("id", "install-url-002")
            .put("type", "APK_INSTALL_URL")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(noUrl) }

        val httpUrl = JSONObject()
            .put("id", "install-url-003")
            .put("type", "APK_INSTALL_URL")
            .put("downloadUrl", "http://example.com/app.apk")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(httpUrl) }

        val badSha = JSONObject()
            .put("id", "install-url-004")
            .put("type", "APK_INSTALL_URL")
            .put("downloadUrl", "https://example.com/app.apk")
            .put("expectedApkSha256", "deadbeef")
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(badSha) }

        val overlongSha = JSONObject()
            .put("id", "install-url-005")
            .put("type", "APK_INSTALL_URL")
            .put("downloadUrl", "https://example.com/app.apk")
            .put("expectedApkSha256", "a".repeat(65))
        assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(overlongSha) }
    }

    @Test
    fun resultRoundTripPreservesLargeDebugOutputMetadata() {
        val result = BridgeResult(
            requestId = "logcat-002",
            status = BridgeResultStatus.SUCCESS,
            risk = BridgeRisk.READ_ONLY,
            detail = "Command completed.",
            output = "AndroidRuntime: FATAL EXCEPTION: main",
            exitCode = 0,
            durationMs = 314,
            completedAtEpochMs = 9000L,
            truncated = true,
        )
        val parsed = BridgeResult.fromJson(result.toJson("test-device"))
        assertEquals(result, parsed)
    }

    @Test
    fun malformedRequestIdIsRejectedBeforeRelayExecution() {
        val json = JSONObject()
            .put("id", "../../not-allowed")
            .put("type", "LOGCAT")
        assertThrows(IllegalArgumentException::class.java) {
            BridgeRequest.fromJson(json)
        }
    }

    @Test
    fun requestExpirationIsDeterministic() {
        val request = BridgeRequest(
            id = "expiry-test",
            type = BridgeCommandType.LOGCAT,
            createdAtEpochMs = 100L,
            expiresAtEpochMs = 200L,
        )
        assertFalse(request.isExpired(199L))
        assertFalse(request.isExpired(200L))
        assertTrue(request.isExpired(201L))
    }
}
