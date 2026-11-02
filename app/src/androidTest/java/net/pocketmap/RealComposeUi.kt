package net.pocketmap

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Read/activate actual semantics on Android's main thread, without replacing its frame clock.
 * MapLibre's physical engine must retain the real main dispatcher throughout UI tests.
 */
internal class RealComposeUi(private val scenario: ActivityScenario<MainActivity>) {
    private fun positionedNodes(): List<Pair<SemanticsNode, androidx.compose.ui.geometry.Offset>> {
        val result = mutableListOf<Pair<SemanticsNode, androidx.compose.ui.geometry.Offset>>()
        fun semantics(node: SemanticsNode, origin: androidx.compose.ui.geometry.Offset) {
            result.add(node to origin); node.children.forEach { semantics(it, origin) }
        }
        fun views(view: View) {
            if (view is ViewRootForTest) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                semantics(view.semanticsOwner.unmergedRootSemanticsNode,
                    androidx.compose.ui.geometry.Offset(location[0].toFloat(), location[1].toFloat()))
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) views(view.getChildAt(index))
        }
        // Dropdowns have separate windows. These instrumentation tests run on API 35.
        check(android.os.Build.VERSION.SDK_INT >= 29)
        if (android.os.Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews().forEach(::views)
        return result
    }

    private fun nodes() = positionedNodes().map { it.first }

    fun screenBounds(tag: String): Rect {
        var result = Rect.Zero
        await("screen bounds $tag") {
            val entry = positionedNodes().firstOrNull { it.first.config.getOrNull(SemanticsProperties.TestTag) == tag }
            if (entry == null) false else {
                result = entry.first.boundsInRoot.translate(entry.second)
                result.width > 0 && result.height > 0
            }
        }
        return result
    }

    fun await(description: String, action: (MainActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            scenario.onActivity { done = action(it) }
            if (done) return
            Thread.sleep(50)
        }
        fail("Timed out: $description")
    }

    fun node(tag: String, action: (SemanticsNode) -> Unit = {}) {
        await("semantics $tag") {
            val node = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag && it.boundsInRoot.width > 0 }
            if (node == null) false else { action(node); true }
        }
    }
    fun click(tag: String) = node(tag) { node ->
        assertTrue("$tag has no click action", node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }
    fun text(value: String) = await("text $value") {
        nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true }
    }
    fun absent(tag: String) = await("absent $tag") {
        nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    }
    fun description(value: String) = await("description $value") {
        nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true }
    }
    fun bounds(tag: String): Rect {
        var result = Rect.Zero
        node(tag) { result = it.boundsInRoot }
        return result
    }
}
