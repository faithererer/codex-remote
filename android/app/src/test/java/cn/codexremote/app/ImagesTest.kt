package cn.codexremote.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImagesTest {
    private val hostA = "11111111-1111-4111-8111-111111111111"
    private val hostB = "22222222-2222-4222-8222-222222222222"
    private val thread = "33333333-3333-4333-8333-333333333333"
    private val instance = "44444444-4444-4444-8444-444444444444"
    @Test fun thumbnailAndFullViewDecodeUseBoundedNonzeroSamples() {
        assertEquals(1, imageSampleSize(160, 90, 320))
        assertEquals(8, imageSampleSize(2048, 1024, 320))
        assertEquals(2, imageSampleSize(4000, 3000, 2048))
        assertTrue(runCatching { imageSampleSize(-1, 90, 320) }.isFailure)
    }
    @Test fun sameThreadIdOnTwoPcsUsesSeparateImageRoutesAndUploads() {
        val a = ThreadKey(hostA, thread); val b = ThreadKey(hostB, thread); val image = UUID.randomUUID().toString()
        assertNotEquals(imageUploadId(a, instance, image), imageUploadId(b, instance, image))
        assertNotEquals(imageApiPath(a, instance, "a".repeat(64)), imageApiPath(b, instance, "a".repeat(64)))
    }
    @Test fun uploadRetriesAreStableButANewInstanceCannotReuseStagedIds() {
        val key = ThreadKey(hostA, thread); val draft = UUID.randomUUID().toString()
        assertEquals(imageUploadId(key, instance, draft), imageUploadId(key, instance, draft))
        assertNotEquals(imageUploadId(key, instance, draft), imageUploadId(key, UUID.randomUUID().toString(), draft))
    }
    @Test fun imageRoutesRejectInjectedPathsAndQueries() {
        val key = ThreadKey(hostA, thread)
        for (id in listOf("../credentials", "a.png?token=oops", "https://untrusted.invalid", "a".repeat(65))) assertTrue(runCatching { imageApiPath(key, instance, id) }.isFailure)
        assertTrue(runCatching { imageApiPath(ThreadKey("host?other", thread), instance, "a".repeat(64)) }.isFailure)
    }
    @Test fun imageOnlyUserMessagesAndToolImagesRemainVisibleOutsideExecutionFolds() {
        val image = JSONObject().put("id", "a".repeat(64)).put("name", "图片")
        val user = JSONObject().put("id", "user").put("kind", "user").put("text", "").put("images", JSONArray().put(image))
        val tool = JSONObject().put("id", "tool").put("kind", "mcpToolCall").put("images", JSONArray().put(image))
        val entries = timelineEntries(listOf(user, tool)); assertEquals(2, entries.size); assertTrue(entries.all { it.message != null })
    }
}
