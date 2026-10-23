package ro.cobrabm.fexdroid

import android.view.KeyEvent
import org.json.JSONObject

/**
 * What an on-screen control does when activated.
 *
 * Keys go through [XInput] (XTEST). Mouse buttons too.
 * Gamepad buttons and axes go through [PadBridge] (virtual Xbox 360 pad),
 * so Steam and games see a real controller.
 */
sealed class ControlAction {
    /** X keycode (already converted with [XInput.xKeycode]); [sticky] toggles hold. */
    data class Key(val xKeycode: Int, val sticky: Boolean = false) : ControlAction() {
        init { require(xKeycode > 0) }
    }

    /** X mouse button: 1 left, 2 middle, 3 right, 4/5 wheel, … */
    data class MouseButton(val button: Int) : ControlAction() {
        init { require(button in 1..9) }
    }

    /**
     * Chord of X keycodes pressed together (modifiers first, then the main key).
     * Down: press in order; up: release in reverse.
     */
    data class KeyCombo(val xKeycodes: List<Int>) : ControlAction() {
        init {
            require(xKeycodes.isNotEmpty()) { "empty combo" }
            require(xKeycodes.all { it > 0 })
        }
    }

    /**
     * Linux key code of an Xbox 360 pad button (BTN_A = 0x130, …).
     * Sent on the virtual pad slot used by on-screen controls.
     */
    data class PadButton(val code: Int) : ControlAction() {
        init { require(code > 0) }
    }

    /**
     * Absolute axis of the virtual pad: [code] is ABS_* (0 = X, 1 = Y, …),
     * [value] is the value to report while the control is held
     * (sticks use the live vector instead; this is for fixed digital directions).
     */
    data class PadAxis(val code: Int, val value: Int) : ControlAction()

    /** Opens the in-game player menu (same as the old Menu key). */
    data object Menu : ControlAction()

    /** Switches what a tap on the picture does (LMB ↔ RMB). */
    data object ToggleTapButton : ControlAction()

    /** Switches what a swipe on the picture does (wheel ↔ middle-drag). */
    data object ToggleSwipeMode : ControlAction()

    data object None : ControlAction()

    fun toJson(): JSONObject = when (this) {
        is Key -> JSONObject().put("t", "key").put("c", xKeycode).put("s", sticky)
        is MouseButton -> JSONObject().put("t", "mouse").put("b", button)
        is KeyCombo -> JSONObject().put("t", "combo").put("c", org.json.JSONArray(xKeycodes))
        is PadButton -> JSONObject().put("t", "padbtn").put("c", code)
        is PadAxis -> JSONObject().put("t", "padaxis").put("c", code).put("v", value)
        Menu -> JSONObject().put("t", "menu")
        ToggleTapButton -> JSONObject().put("t", "tap")
        ToggleSwipeMode -> JSONObject().put("t", "swipe")
        None -> JSONObject().put("t", "none")
    }

    companion object {
        fun fromJson(o: JSONObject): ControlAction = when (o.optString("t")) {
            "key" -> Key(o.getInt("c"), o.optBoolean("s", false))
            "mouse" -> MouseButton(o.getInt("b"))
            "combo" -> {
                val arr = o.optJSONArray("c")
                val codes = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val c = arr.optInt(i, -1); if (c > 0) add(c)
                    }
                }
                if (codes.isEmpty()) None else KeyCombo(codes)
            }
            "padbtn" -> PadButton(o.getInt("c"))
            "padaxis" -> PadAxis(o.getInt("c"), o.getInt("v"))
            "menu" -> Menu
            "tap" -> ToggleTapButton
            "swipe" -> ToggleSwipeMode
            else -> None
        }

        // —— helpers to build actions from names (compat with old OnScreenKeys layout strings) ——

        /** Android key code → [Key] action, or null if unknown / unmapped. */
        fun fromAndroidKey(androidKey: Int, sticky: Boolean = false): Key? {
            val x = XInput.xKeycode(androidKey)
            return if (x < 0) null else Key(x, sticky)
        }

        /** Parse a single token from the old layout string ("Esc", "shift", "Mouse", …). */
        fun fromLayoutName(name: String): ControlAction? {
            val n = name.lowercase()
            when (n) {
                "mouse" -> return ToggleTapButton
                "swipe" -> return ToggleSwipeMode
                "menu" -> return Menu
            }
            val stickyCode = when (n) {
                "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
                "ctrl" -> KeyEvent.KEYCODE_CTRL_LEFT
                "alt" -> KeyEvent.KEYCODE_ALT_LEFT
                else -> null
            }
            if (stickyCode != null) return fromAndroidKey(stickyCode, sticky = true)

            val named = mapOf(
                "esc" to KeyEvent.KEYCODE_ESCAPE, "tab" to KeyEvent.KEYCODE_TAB,
                "space" to KeyEvent.KEYCODE_SPACE, "enter" to KeyEvent.KEYCODE_ENTER,
                "back" to KeyEvent.KEYCODE_DEL, "del" to KeyEvent.KEYCODE_FORWARD_DEL,
                "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
                "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            )
            named[n]?.let { return fromAndroidKey(it) }

            n.singleOrNull()?.let { c ->
                when (c) {
                    in 'a'..'z' -> return fromAndroidKey(KeyEvent.KEYCODE_A + (c - 'a'))
                    in '0'..'9' -> return fromAndroidKey(KeyEvent.KEYCODE_0 + (c - '0'))
                    else -> {}
                }
            }
            Regex("f(\\d{1,2})").matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..12 }
                ?.let { return fromAndroidKey(KeyEvent.KEYCODE_F1 + it - 1) }
            return null
        }

        // Xbox 360 pad button codes (same as Gamepads.BUTTONS / kernel xpad).
        const val BTN_A = 0x130
        const val BTN_B = 0x131
        const val BTN_X = 0x133
        const val BTN_Y = 0x134
        const val BTN_TL = 0x136   // LB
        const val BTN_TR = 0x137   // RB
        const val BTN_SELECT = 0x13a
        const val BTN_START = 0x13b
        const val BTN_MODE = 0x13c
        const val BTN_THUMBL = 0x13d
        const val BTN_THUMBR = 0x13e

        // ABS codes
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_Z = 0x02      // left trigger 0..255
        const val ABS_RX = 0x03
        const val ABS_RY = 0x04
        const val ABS_RZ = 0x05     // right trigger
        const val ABS_HAT0X = 0x10
        const val ABS_HAT0Y = 0x11
    }
}
