package net.pocketmap

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ImageButton
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PinHoldGestureTest {
    private lateinit var button: ImageButton
    private lateinit var gesture: PinHoldGesture
    private var clicks = 0
    private var holds = 0
    @Before fun create() {
        button = ImageButton(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 100, 100)
            setOnClickListener { clicks++ }
        }
        gesture = PinHoldGesture(button) { holds++ }
    }
    private fun touch(action: Int, x: Float = 50f) {
        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, 50f, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun opensAtOneSecondWhileStillHeldAndReleaseDoesNotAddAnotherPin() {
        touch(MotionEvent.ACTION_DOWN)
        advance(999)
        assertEquals(0, holds)
        advance(1)
        assertEquals(1, holds)
        assertEquals(0, clicks)
        advance(2_000)
        touch(MotionEvent.ACTION_UP)
        assertEquals(1, holds)
        assertEquals(0, clicks)
    }
    @Test fun ordinaryTapSavesOnceWithoutOpeningText() {
        touch(MotionEvent.ACTION_DOWN)
        advance(200)
        touch(MotionEvent.ACTION_UP)
        advance(2_000)
        assertEquals(1, clicks)
        assertEquals(0, holds)
    }
    @Test fun draggingAwayOrCancellingCannotCreateAPinOrOpenText() {
        for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            touch(MotionEvent.ACTION_DOWN)
            advance(500)
            touch(action, 300f)
            advance(1_000)
            touch(MotionEvent.ACTION_UP)
        }
        touch(MotionEvent.ACTION_DOWN)
        gesture.cancel() // Activity pause/lock or view detach cancels the pending hold.
        advance(1_000)
        touch(MotionEvent.ACTION_UP)
        assertEquals(0, clicks)
        assertEquals(0, holds)
    }
    @Test fun accessibilityLongClickStillOffersText() {
        assertTrue(button.performLongClick())
        assertEquals(1, holds)
        assertEquals(0, clicks)
    }
}
