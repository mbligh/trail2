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
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun semantics(node: SemanticsNode) { result.add(node); node.children.forEach(::semantics) }
        fun views(view: View) {
            if (view is ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) for (index in 0 until view.childCount) views(view.getChildAt(index))
        }
        // Dropdowns have separate windows. These instrumentation tests run on API 35.
        check(android.os.Build.VERSION.SDK_INT >= 29)
        if (android.os.Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews().forEach(::views)
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
    fun bounds(tag: String): Rect {
        var result = Rect.Zero
        node(tag) { result = it.boundsInRoot }
        return result
    }
}
