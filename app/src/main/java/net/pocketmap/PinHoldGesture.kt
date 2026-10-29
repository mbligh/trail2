package net.pocketmap

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** A one-second hold fires while the finger is down and consumes the following tap. */
internal class PinHoldGesture(private val button: View, onHold: () -> Unit) : View.OnTouchListener, View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private var armed = false
    private var held = false
    private val hold = Runnable {
        if (armed) {
            held = true
            button.isPressed = false
            button.performLongClick() // Also provides Android's standard haptic feedback.
        }
    }

    init {
        button.setOnLongClickListener { onHold(); true } // Keep accessibility long-click support.
        button.setOnTouchListener(this)
        button.addOnAttachStateChangeListener(this)
    }

    @Suppress("ClickableViewAccessibility") // Short taps explicitly call performClick().
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (!button.isEnabled) return false
                armed = true
                button.isPressed = true
                handler.postDelayed(hold, 1_000)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < -slop || event.x >= button.width + slop ||
                    event.y < -slop || event.y >= button.height + slop) cancel()
            }
            MotionEvent.ACTION_UP -> {
                val click = armed && !held
                cancel()
                if (click) button.performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        armed = false
        held = false
        button.isPressed = false
    }

    override fun onViewDetachedFromWindow(view: View) = cancel()
    override fun onViewAttachedToWindow(view: View) {}
}
