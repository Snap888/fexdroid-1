package org.anlr.linuxruntime

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.roundToInt

/** JNI port of the original FEXDroid Xvfb shared-memory -> Android Surface renderer. */
internal object AnlrDisplayBridge {
    init { System.loadLibrary("anlr_display") }
    external fun start(surface: Surface, sockPath: String, shmid: Int, fps: Int, presentPath: String): String
    external fun stop()
    external fun frames(): Long
    external fun changedFrames(): Long
    external fun directFrames(): Long
}

/** XTEST input injector and PulseAudio FIFO -> AAudio output bridge. */
internal object AnlrXInput {
    init { System.loadLibrary("anlr_io") }
    external fun connect(display: Int): String
    external fun key(keycode: Int, down: Boolean)
    external fun button(button: Int, down: Boolean)
    external fun moveTo(x: Int, y: Int)
    external fun moveBy(dx: Int, dy: Int)

    fun keycode(event: KeyEvent): Int {
        if (event.scanCode > 0) return (event.scanCode + 8).coerceIn(8, 255)
        return keyMap[event.keyCode]?.plus(8) ?: -1
    }

    private val keyMap = buildMap {
        val rows = listOf("qwertyuiop" to 16, "asdfghjkl" to 30, "zxcvbnm" to 44)
        rows.forEach { (keys, start) -> keys.forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + c - 'a', start + i) } }
        for (d in 1..9) put(KeyEvent.KEYCODE_0 + d, 1 + d)
        put(KeyEvent.KEYCODE_0, 11)
        val fixed = mapOf(
            KeyEvent.KEYCODE_ESCAPE to 1, KeyEvent.KEYCODE_MINUS to 12, KeyEvent.KEYCODE_EQUALS to 13,
            KeyEvent.KEYCODE_DEL to 14, KeyEvent.KEYCODE_TAB to 15, KeyEvent.KEYCODE_LEFT_BRACKET to 26,
            KeyEvent.KEYCODE_RIGHT_BRACKET to 27, KeyEvent.KEYCODE_ENTER to 28,
            KeyEvent.KEYCODE_CTRL_LEFT to 29, KeyEvent.KEYCODE_SEMICOLON to 39,
            KeyEvent.KEYCODE_APOSTROPHE to 40, KeyEvent.KEYCODE_GRAVE to 41,
            KeyEvent.KEYCODE_SHIFT_LEFT to 42, KeyEvent.KEYCODE_BACKSLASH to 43,
            KeyEvent.KEYCODE_COMMA to 51, KeyEvent.KEYCODE_PERIOD to 52, KeyEvent.KEYCODE_SLASH to 53,
            KeyEvent.KEYCODE_SHIFT_RIGHT to 54, KeyEvent.KEYCODE_ALT_LEFT to 56,
            KeyEvent.KEYCODE_SPACE to 57, KeyEvent.KEYCODE_CTRL_RIGHT to 97,
            KeyEvent.KEYCODE_ALT_RIGHT to 100, KeyEvent.KEYCODE_MOVE_HOME to 102,
            KeyEvent.KEYCODE_DPAD_UP to 103, KeyEvent.KEYCODE_PAGE_UP to 104,
            KeyEvent.KEYCODE_DPAD_LEFT to 105, KeyEvent.KEYCODE_DPAD_RIGHT to 106,
            KeyEvent.KEYCODE_MOVE_END to 107, KeyEvent.KEYCODE_DPAD_DOWN to 108,
            KeyEvent.KEYCODE_PAGE_DOWN to 109, KeyEvent.KEYCODE_INSERT to 110,
            KeyEvent.KEYCODE_FORWARD_DEL to 111
        )
        putAll(fixed)
        for (f in 1..10) put(KeyEvent.KEYCODE_F1 + f - 1, 58 + f)
        put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)
    }
}

internal object AnlrAudioBridge {
    external fun start(fifo: String): String
    external fun frames(): Long
    external fun gaps(): Int
    external fun holes(): Int
}

/**
 * Android surface receiving the live Xvfb framebuffer. Input is injected into the same X server
 * using XTEST; physical keyboards preserve evdev scancodes (including Ctrl/F-keys).
 */
internal class AnlrDesktopSurface(
    context: Context,
    private val guestWidth: Int,
    private val guestHeight: Int,
    private val shmSocket: String,
    private val shmid: Int,
    private val fps: Int = 60
) : SurfaceView(context), SurfaceHolder.Callback {
    @Volatile var bridgeStatus: String = "Waiting for Android Surface"
        private set
    private var downX = 0f
    private var downY = 0f
    private var touchDown = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        bridgeStatus = runCatching {
            val status = AnlrDisplayBridge.start(holder.surface, shmSocket, shmid, fps, "")
            check(status.startsWith("bridge running")) { status }
            val input = AnlrXInput.connect(0)
            check(input.startsWith("X input connected")) { input }
            "$status; $input"
        }.getOrElse { "Display bridge failed: ${it.message}" }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { AnlrDisplayBridge.stop() }
        bridgeStatus = "Surface detached"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x; downY = event.y; touchDown = true
                AnlrXInput.moveTo((event.x / width.coerceAtLeast(1) * guestWidth).roundToInt().coerceIn(0, guestWidth - 1),
                    (event.y / height.coerceAtLeast(1) * guestHeight).roundToInt().coerceIn(0, guestHeight - 1))
                AnlrXInput.button(1, true)
            }
            MotionEvent.ACTION_MOVE -> if (touchDown) {
                AnlrXInput.moveTo((event.x / width.coerceAtLeast(1) * guestWidth).roundToInt().coerceIn(0, guestWidth - 1),
                    (event.y / height.coerceAtLeast(1) * guestHeight).roundToInt().coerceIn(0, guestHeight - 1))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchDown) AnlrXInput.button(1, false)
                touchDown = false
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) click(4)
            if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0) click(5)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE) {
            AnlrXInput.moveTo((event.x / width.coerceAtLeast(1) * guestWidth).roundToInt().coerceIn(0, guestWidth - 1),
                (event.y / height.coerceAtLeast(1) * guestHeight).roundToInt().coerceIn(0, guestHeight - 1))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun click(button: Int) { AnlrXInput.button(button, true); AnlrXInput.button(button, false) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val code = AnlrXInput.keycode(event)
        if (code >= 0) { AnlrXInput.key(code, true); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val code = AnlrXInput.keycode(event)
        if (code >= 0) { AnlrXInput.key(code, false); return true }
        return super.onKeyUp(keyCode, event)
    }
}
