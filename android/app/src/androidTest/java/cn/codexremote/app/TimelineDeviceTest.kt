package cn.codexremote.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Ignore

@Ignore("Accessibility timing is not validated on vivo; use the documented manual UI checks")
class TimelineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val key = ThreadKey("ui-test-host", "ui-test-thread")
    private fun message(id: String, text: String, kind: String = "agentMessage") = JSONObject().put("id", id).put("text", text).put("kind", kind)
    private fun history() = JSONObject().put("messages", JSONArray((0 until 35).map { message("m$it", "历史消息 $it\n正文内容\n另一行正文") })).put("latestReasoning", JSONObject().put("text", "最新思考状态"))
    @Before fun launch() {
        instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(80) {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { activity = it } }
            if (::activity.isInitialized) return
            Thread.sleep(100)
        }
        error("Target activity did not enter resumed state")
    }
    @After fun close() { if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() } }
    private fun show(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync { (activity.application as RemoteApplication).repository.foreground(false); activity.setContent { MaterialTheme { content() } } }
        Thread.sleep(700)
    }
    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(nodes(it)) }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != "cn.codexremote.app") return null
        return nodes(root).firstOrNull { it.isVisibleToUser && (it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true) }
    }
    private fun await(text: String): AccessibilityNodeInfo {
        repeat(50) { find(text)?.let { return it }; Thread.sleep(100) }
        error("Visible fixture text missing: $text")
    }
    private fun tap(text: String) {
        val node = await(text)
        val bounds = Rect().also(node::getBoundsInScreen)
        touch(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterX(), bounds.exactCenterY(), 80)
        Thread.sleep(400)
    }
    private fun touch(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        require(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "cn.codexremote.app") { "Fixture is no longer foreground; refusing input" }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long, x: Float, y: Float) { val event = MotionEvent.obtain(down, time, action, x, y, 0); event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle() }
        event(MotionEvent.ACTION_DOWN, down, x1, y1)
        for (step in 1..12) { Thread.sleep(duration / 12); val fraction = step / 12f; event(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction) }
        event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), x2, y2)
    }
    @Test fun opensAtLatestAndShowsOnlyRollingThought() {
        val view = mutableStateOf(history())
        show { Column(Modifier.fillMaxSize()) { ConversationTimeline(key, view.value, Modifier.weight(1f)) {}; LatestThought(view.value, false, false) {} } }
        await("历史消息 34"); await("最新思考状态")
        instrumentation.runOnMainSync { view.value = JSONObject(view.value.toString()).put("latestReasoning", JSONObject().put("text", "替换后的思考状态")) }
        await("替换后的思考状态"); assertNull(find("最新思考状态"))
        instrumentation.runOnMainSync { val next = JSONObject(view.value.toString()); next.getJSONArray("messages").put(message("new", "底部自动跟随的新消息")); view.value = next }
        await("底部自动跟随的新消息")
    }
    @Test fun readingHistoryStaysPutAndCanJumpToLatest() {
        val view = mutableStateOf(history())
        show { ConversationTimeline(key, view.value, Modifier.fillMaxSize()) {} }
        val bounds = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        touch(bounds.exactCenterX(), bounds.height() * .35f, bounds.exactCenterX(), bounds.height() * .8f, 600)
        await("回到最新")
        instrumentation.runOnMainSync { val next = JSONObject(view.value.toString()); next.getJSONArray("messages").put(message("new", "新到的最后一轮")); view.value = next }
        Thread.sleep(500)
        await("回到最新"); assertNull(find("新到的最后一轮"))
        tap("回到最新"); await("新到的最后一轮")
    }
    @Test fun commandsAndResultsAreSeparateAndCollapsed() {
        val command = message("c", "legacy", "commandExecution").put("command", "echo test-command").put("output", "test-result-only").put("exitCode", 0).put("status", "completed")
        val view = JSONObject().put("messages", JSONArray().put(command))
        show { ConversationTimeline(key, view, Modifier.fillMaxSize()) {} }
        await("执行记录 · 1 项"); assertNull(find("test-result-only"))
        tap("执行记录 · 1 项"); await("echo test-command")
        tap("执行结果 · 1 行"); await("test-result-only")
    }
}
