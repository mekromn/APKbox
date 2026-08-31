package com.mekromn.apkbox.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScanRuleTest {
    @Test
    fun ruleRequiresEveryKeywordAndMatchesCaseInsensitively() {
        val rule = AutoScanManager.Rule(
            id = "rule-1",
            projectId = "project-1",
            keywords = listOf("PixelCamera", "P9PXL", "HDR"),
        )

        assertTrue(rule.matches("PixelCamera_9.9.106_P9PXL_R45_HDR_FIX.apk"))
        assertTrue(rule.matches("pixelcamera_p9pxl_hdr_test.APK"))
        assertFalse(rule.matches("PixelCamera_P9PXL_no_feature.apk"))
        assertFalse(rule.matches("OtherCamera_P9PXL_HDR.apk"))
    }

    @Test
    fun blankKeywordsNeverBecomeCatchAllRule() {
        val rule = AutoScanManager.Rule(
            id = "rule-2",
            projectId = "project-1",
            keywords = listOf("", "   "),
        )

        assertFalse(rule.matches("anything.apk"))
    }
}
