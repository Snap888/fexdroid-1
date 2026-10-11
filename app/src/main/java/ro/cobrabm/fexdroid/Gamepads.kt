package ro.cobrabm.fexdroid

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import kotlin.math.roundToInt

/** JNI bindings to cpp/pad_bridge.c (gamepads as /dev/input/event2xx for FEX guests). */
object PadBridge {
    init { System.loadLibrary("fxio") }

    @JvmStatic external fun start(dir: String): String
    @JvmStatic external fun stop()
    @JvmStatic external fun open(slot: Int): Boolean
    @JvmStatic external fun close(slot: Int)
    /** [events] holds [count] (type, code, value) triplets; SYN_REPORT is added. */
    @JvmStatic external fun send(slot: Int, events: IntArray, count: Int)
    @JvmStatic external fun readers(slot: Int): Int
}

/**
 * Bluetooth/USB gamepads of the phone, shown to Steam and the games as Xbox 360 pads
 * (FEX patch 0006; NOTES N-054). Each Android gamepad gets a slot; the slot's events use the
 * codes of the kernel's xpad driver, which SDL and Steam map without configuration.
 * Gamepad input never reaches the X server while a session runs.
 */
object Gamepads {
    private const val MAX_PADS = 4
    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val ABS_X = 0x00; private const val ABS_Y = 0x01; private const val ABS_Z = 0x02
    private const val ABS_RX = 0x03; private const val ABS_RY = 0x04; private const val ABS_RZ = 0x05
    private const val ABS_HAT0X = 0x10; private const val ABS_HAT0Y = 0x11

    private val BUTTONS = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0x130, KeyEvent.KEYCODE_BUTTON_B to 0x131,
        KeyEvent.KEYCODE_BUTTON_X to 0x133, KeyEvent.KEYCODE_BUTTON_Y to 0x134,
        KeyEvent.KEYCODE_BUTTON_L1 to 0x136, KeyEvent.KEYCODE_BUTTON_R1 to 0x137,
        KeyEvent.KEYCODE_BUTTON_SELECT to 0x13a, KeyEvent.KEYCODE_BACK to 0x13a,
        KeyEvent.KEYCODE_BUTTON_START to 0x13b, KeyEvent.KEYCODE_MENU to 0x13b,
        KeyEvent.KEYCODE_BUTTON_MODE to 0x13c,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 0x13d, KeyEvent.KEYCODE_BUTTON_THUMBR to 0x13e,
    )

    private val DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    /** Per pad: where its right stick and triggers are, and what was sent last. */
    private class Pad(val slot: Int, device: InputDevice) {
        val rightX: Int; val rightY: Int; val leftTrigger: Int; val rightTrigger: Int
        val ranges = HashMap<Int, InputDevice.MotionRange>()
        val last = IntArray(0x12) { Int.MIN_VALUE }
        var dpadX = 0; var dpadY = 0

        init {
            for (r in device.motionRanges) if (r.isFromSource(InputDevice.SOURCE_JOYSTICK)) ranges[r.axis] = r
            val analogTriggers = MotionEvent.AXIS_LTRIGGER in ranges || MotionEvent.AXIS_BRAKE in ranges
            // Most pads: right stick on Z/RZ, triggers on LTRIGGER/RTRIGGER (or BRAKE/GAS). Older
            // ones put the right stick on RX/RY and the triggers on Z/RZ.
            if (!analogTriggers && MotionEvent.AXIS_RX in ranges && MotionEvent.AXIS_RY in ranges) {
                rightX = MotionEvent.AXIS_RX; rightY = MotionEvent.AXIS_RY
                leftTrigger = MotionEvent.AXIS_Z; rightTrigger = MotionEvent.AXIS_RZ
            } else {
                rightX = MotionEvent.AXIS_Z; rightY = MotionEvent.AXIS_RZ
                leftTrigger = if (MotionEvent.AXIS_LTRIGGER in ranges) MotionEvent.AXIS_LTRIGGER else MotionEvent.AXIS_BRAKE
                rightTrigger = if (MotionEvent.AXIS_RTRIGGER in ranges) MotionEvent.AXIS_RTRIGGER else MotionEvent.AXIS_GAS
            }
        }
    }

    private val pads = HashMap<Int, Pad>() // Android device id -> pad. Main thread only.
    @Volatile private var on = false
    private var inputManager: InputManager? = null
    private val events = IntArray(64 * 3)

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { attach(id) }
        override fun onInputDeviceRemoved(id: Int) { pads.remove(id)?.let { PadBridge.close(it.slot) } }
        override fun onInputDeviceChanged(id: Int) {}
    }

    fun isGamepad(d: InputDevice?): Boolean =
        d != null && !d.isVirtual &&
            (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    /**
     * Session start: the gamepad directory exists (FEX then shows it as /dev/input, empty or
     * not) and every gamepad connected now, or later, gets a node. [dir] is
     * <rootfs>/usr/share/fex-emu/fexdroid-input (FEX's global data directory).
     */
    fun start(ctx: Context, dir: File, log: (String) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (on) return@post
            log(PadBridge.start(dir.path))
            on = true
            val im = ctx.getSystemService(InputManager::class.java)
            inputManager = im
            im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
            for (id in im.inputDeviceIds) attach(id)?.let { log("Gamepad ${it.second} -> /dev/input/event${200 + it.first}") }
        }
    }

    fun stop() {
        Handler(Looper.getMainLooper()).post {
            if (!on) return@post
            on = false
            inputManager?.unregisterInputDeviceListener(listener)
            inputManager = null
            pads.clear()
            PadBridge.stop()
        }
    }

    /** For the report: "name: slot, readers" per pad. */
    fun describe(): String = if (!on) "off" else if (pads.isEmpty()) "on, none connected" else
        pads.entries.joinToString("; ") { (id, p) ->
            "${InputDevice.getDevice(id)?.name ?: id} = event${200 + p.slot}, open by ${PadBridge.readers(p.slot)}"
        }

    private fun attach(id: Int): Pair<Int, String>? {
        if (!on || id in pads) return null
        val d = InputDevice.getDevice(id)
        if (!isGamepad(d)) return null
        val used = pads.values.map { it.slot }.toSet()
        val slot = (0 until MAX_PADS).firstOrNull { it !in used } ?: return null
        if (!PadBridge.open(slot)) return null
        pads[id] = Pad(slot, d!!)
        return slot to d.name
    }

    private fun padFor(deviceId: Int): Pad? = pads[deviceId] ?: attach(deviceId)?.let { pads[deviceId] }

    /** A key of a gamepad: true when it went to the pad (it must then not reach the X server). */
    fun onKey(e: KeyEvent): Boolean {
        if (!on || !isGamepad(e.device)) return false
        val k = e.keyCode
        if (!KeyEvent.isGamepadButton(k) && k !in DPAD && k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_MENU) return false
        val pad = padFor(e.deviceId) ?: return false
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount > 0) return true
        var n = 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                pad.dpadY = if (!down) 0 else if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                n = put(n, pad, EV_ABS, ABS_HAT0Y, pad.dpadY)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                pad.dpadX = if (!down) 0 else if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                n = put(n, pad, EV_ABS, ABS_HAT0X, pad.dpadX)
            }
            // Digital triggers (pads without analog ones).
            KeyEvent.KEYCODE_BUTTON_L2 -> if (MotionEvent.AXIS_LTRIGGER !in pad.ranges && MotionEvent.AXIS_BRAKE !in pad.ranges)
                n = put(n, pad, EV_ABS, ABS_Z, if (down) 255 else 0)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (MotionEvent.AXIS_RTRIGGER !in pad.ranges && MotionEvent.AXIS_GAS !in pad.ranges)
                n = put(n, pad, EV_ABS, ABS_RZ, if (down) 255 else 0)
            else -> {
                val code = BUTTONS[e.keyCode] ?: return true // Other pad buttons: nothing on an Xbox 360 pad.
                events[0] = EV_KEY; events[1] = code; events[2] = if (down) 1 else 0
                n = 1
            }
        }
        if (n > 0) PadBridge.send(pad.slot, events, n)
        return true
    }

    /** Sticks, triggers and hat of a gamepad. */
    fun onMotion(e: MotionEvent): Boolean {
        if (!on || !e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (!isGamepad(e.device)) return false
        val pad = padFor(e.deviceId) ?: return false
        var n = 0
        n = put(n, pad, EV_ABS, ABS_X, stick(e, pad, MotionEvent.AXIS_X))
        n = put(n, pad, EV_ABS, ABS_Y, stick(e, pad, MotionEvent.AXIS_Y))
        n = put(n, pad, EV_ABS, ABS_RX, stick(e, pad, pad.rightX))
        n = put(n, pad, EV_ABS, ABS_RY, stick(e, pad, pad.rightY))
        if (pad.leftTrigger in pad.ranges) n = put(n, pad, EV_ABS, ABS_Z, trigger(e, pad, pad.leftTrigger))
        if (pad.rightTrigger in pad.ranges) n = put(n, pad, EV_ABS, ABS_RZ, trigger(e, pad, pad.rightTrigger))
        if (MotionEvent.AXIS_HAT_X in pad.ranges) n = put(n, pad, EV_ABS, ABS_HAT0X, e.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt().coerceIn(-1, 1))
        if (MotionEvent.AXIS_HAT_Y in pad.ranges) n = put(n, pad, EV_ABS, ABS_HAT0Y, e.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt().coerceIn(-1, 1))
        if (n > 0) PadBridge.send(pad.slot, events, n)
        return true
    }

    /** Adds an ABS event when the value changed since the last one sent. */
    private fun put(n: Int, pad: Pad, type: Int, code: Int, value: Int): Int {
        if (type == EV_ABS && pad.last[code] == value) return n
        if (type == EV_ABS) pad.last[code] = value
        events[n * 3] = type; events[n * 3 + 1] = code; events[n * 3 + 2] = value
        return n + 1
    }

    private fun stick(e: MotionEvent, pad: Pad, axis: Int): Int {
        if (axis !in pad.ranges) return 0
        return (e.getAxisValue(axis) * 32767f).roundToInt().coerceIn(-32768, 32767)
    }

    private fun trigger(e: MotionEvent, pad: Pad, axis: Int): Int {
        val r = pad.ranges[axis] ?: return 0
        val span = (r.max - r.min).takeIf { it > 0f } ?: return 0
        return ((e.getAxisValue(axis) - r.min) / span * 255f).roundToInt().coerceIn(0, 255)
    }
}
