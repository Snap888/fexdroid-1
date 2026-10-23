package ro.cobrabm.fexdroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One on-screen control: position is normalised to the window (0..1 from top-left),
 * so a layout survives different phone sizes and letterboxing.
 *
 * [scale] multiplies the default size of that element type.
 * [opacity] is the resting alpha (pressed state is drawn brighter by the renderer).
 */
sealed class ControlElement {
    abstract val id: String
    abstract var x: Float
    abstract var y: Float
    abstract var scale: Float
    abstract var opacity: Float
    abstract var visible: Boolean

    /** Label shown on the control (empty = icon only / no text). */
    abstract var label: String

    fun copyPlacement(from: ControlElement) {
        x = from.x; y = from.y; scale = from.scale; opacity = from.opacity; visible = from.visible
    }

    data class Button(
        override val id: String = newId(),
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 1f,
        override var opacity: Float = 0.55f,
        override var visible: Boolean = true,
        override var label: String = "",
        var action: ControlAction = ControlAction.None,
        /** If true, one tap toggles the action (for sticky keys / modes). */
        var toggle: Boolean = false,
    ) : ControlElement()

    /**
     * Virtual analog (or digital) stick.
     * Live vector is sent as pad axes [axisX]/[axisY] (default left stick).
     * In [StickMode.Digital4]/[Digital8] the stick also fires the optional direction actions.
     */
    data class Stick(
        override val id: String = newId(),
        override var x: Float = 0.18f,
        override var y: Float = 0.72f,
        override var scale: Float = 1f,
        override var opacity: Float = 0.55f,
        override var visible: Boolean = true,
        override var label: String = "",
        var mode: StickMode = StickMode.Analog,
        var deadzone: Float = 0.12f,
        var sensitivity: Float = 1f,
        /** Pad ABS codes for the live vector (−32768..32767). */
        var axisX: Int = ControlAction.ABS_X,
        var axisY: Int = ControlAction.ABS_Y,
        /** Optional discrete actions when the stick is in a digital mode. */
        var up: ControlAction = ControlAction.None,
        var down: ControlAction = ControlAction.None,
        var left: ControlAction = ControlAction.None,
        var right: ControlAction = ControlAction.None,
    ) : ControlElement()

    /**
     * Four- or eight-way D-pad. Directions are either pad HAT axes or arbitrary actions
     * (keys, pad buttons, …).
     */
    data class DPad(
        override val id: String = newId(),
        override var x: Float = 0.18f,
        override var y: Float = 0.72f,
        override var scale: Float = 1f,
        override var opacity: Float = 0.55f,
        override var visible: Boolean = true,
        override var label: String = "",
        var eightWay: Boolean = true,
        /** When true, directions drive ABS_HAT0X/Y instead of the action fields. */
        var useHat: Boolean = true,
        var up: ControlAction = ControlAction.None,
        var down: ControlAction = ControlAction.None,
        var left: ControlAction = ControlAction.None,
        var right: ControlAction = ControlAction.None,
    ) : ControlElement()

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .put("scale", scale.toDouble())
            .put("opacity", opacity.toDouble())
            .put("visible", visible)
            .put("label", label)
        when (this) {
            is Button -> o.put("type", "button")
                .put("action", action.toJson())
                .put("toggle", toggle)
            is Stick -> o.put("type", "stick")
                .put("mode", mode.name)
                .put("deadzone", deadzone.toDouble())
                .put("sensitivity", sensitivity.toDouble())
                .put("axisX", axisX)
                .put("axisY", axisY)
                .put("up", up.toJson())
                .put("down", down.toJson())
                .put("left", left.toJson())
                .put("right", right.toJson())
            is DPad -> o.put("type", "dpad")
                .put("eightWay", eightWay)
                .put("useHat", useHat)
                .put("up", up.toJson())
                .put("down", down.toJson())
                .put("left", left.toJson())
                .put("right", right.toJson())
        }
        return o
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)

        fun fromJson(o: JSONObject): ControlElement? {
            val id = o.optString("id").ifEmpty { newId() }
            val x = o.optDouble("x", 0.5).toFloat()
            val y = o.optDouble("y", 0.5).toFloat()
            val scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.35f, 2.5f)
            val opacity = o.optDouble("opacity", 0.55).toFloat().coerceIn(0.1f, 1f)
            val visible = o.optBoolean("visible", true)
            val label = o.optString("label", "")
            return when (o.optString("type")) {
                "button" -> Button(
                    id, x, y, scale, opacity, visible, label,
                    action = ControlAction.fromJson(o.optJSONObject("action") ?: JSONObject()),
                    toggle = o.optBoolean("toggle", false),
                )
                "stick" -> Stick(
                    id, x, y, scale, opacity, visible, label,
                    mode = runCatching { StickMode.valueOf(o.optString("mode", "Analog")) }
                        .getOrDefault(StickMode.Analog),
                    deadzone = o.optDouble("deadzone", 0.12).toFloat().coerceIn(0f, 0.5f),
                    sensitivity = o.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.25f, 3f),
                    axisX = o.optInt("axisX", ControlAction.ABS_X),
                    axisY = o.optInt("axisY", ControlAction.ABS_Y),
                    up = ControlAction.fromJson(o.optJSONObject("up") ?: JSONObject()),
                    down = ControlAction.fromJson(o.optJSONObject("down") ?: JSONObject()),
                    left = ControlAction.fromJson(o.optJSONObject("left") ?: JSONObject()),
                    right = ControlAction.fromJson(o.optJSONObject("right") ?: JSONObject()),
                )
                "dpad" -> DPad(
                    id, x, y, scale, opacity, visible, label,
                    eightWay = o.optBoolean("eightWay", true),
                    useHat = o.optBoolean("useHat", true),
                    up = ControlAction.fromJson(o.optJSONObject("up") ?: JSONObject()),
                    down = ControlAction.fromJson(o.optJSONObject("down") ?: JSONObject()),
                    left = ControlAction.fromJson(o.optJSONObject("left") ?: JSONObject()),
                    right = ControlAction.fromJson(o.optJSONObject("right") ?: JSONObject()),
                )
                else -> null
            }
        }
    }
}

enum class StickMode {
    /** Continuous −1..1 vector → pad axes. */
    Analog,
    /** Snap to 4 directions (cardinal). */
    Digital4,
    /** Snap to 8 directions. */
    Digital8,
}

/**
 * A named layout of on-screen controls. Serialised as JSON for SharedPreferences / files.
 *
 * [version] is bumped when the schema changes so old profiles can be migrated.
 */
data class ControlProfile(
    val name: String,
    val elements: List<ControlElement>,
    val version: Int = CURRENT_VERSION,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("name", name)
        .put("elements", JSONArray().also { arr ->
            for (e in elements) arr.put(e.toJson())
        })

    fun toJsonString(indent: Int = 0): String =
        if (indent > 0) toJson().toString(indent) else toJson().toString()

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(o: JSONObject): ControlProfile {
            val elements = buildList {
                val arr = o.optJSONArray("elements") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val el = arr.optJSONObject(i)?.let { ControlElement.fromJson(it) }
                    if (el != null) add(el)
                }
            }
            return ControlProfile(
                name = o.optString("name", "Profile"),
                elements = elements,
                version = o.optInt("version", 1),
            )
        }

        fun fromJsonString(s: String): ControlProfile? =
            runCatching { fromJson(JSONObject(s)) }.getOrNull()

        /**
         * Build a profile that matches the current side-strip behaviour
         * ([OnScreenKeys] left/right strings), so existing settings keep working
         * when the free-placement renderer is switched on.
         */
        fun fromSideStrips(left: String, right: String): ControlProfile {
            val elements = mutableListOf<ControlElement>()
            fun addStrip(names: List<String>, atLeft: Boolean) {
                if (names.isEmpty()) return
                val x = if (atLeft) 0.04f else 0.96f
                val step = 1f / (names.size + 1)
                names.forEachIndexed { i, raw ->
                    val action = ControlAction.fromLayoutName(raw) ?: return@forEachIndexed
                    val label = when (action) {
                        is ControlAction.ToggleTapButton -> "Mouse"
                        is ControlAction.ToggleSwipeMode -> "Swipe"
                        is ControlAction.Menu -> ""
                        else -> if (raw.length == 1) raw.uppercase() else raw.replaceFirstChar { it.uppercase() }
                    }
                    val toggle = action is ControlAction.Key && action.sticky ||
                        action is ControlAction.ToggleTapButton ||
                        action is ControlAction.ToggleSwipeMode
                    elements += ControlElement.Button(
                        x = x,
                        y = step * (i + 1),
                        scale = 0.85f,
                        opacity = 0.45f,
                        label = label,
                        action = action,
                        toggle = toggle,
                    )
                }
            }
            addStrip(left.split(' ', ',', '\n').filter { it.isNotBlank() }, atLeft = true)
            // Menu first on the right, like PlayerScreen does today.
            addStrip((listOf("Menu") + right.split(' ', ',', '\n').filter { it.isNotBlank() }), atLeft = false)
            return ControlProfile("Side strips", elements)
        }

        /** Default gamepad-style layout: left stick + right face buttons + D-pad. */
        fun defaultGamepad(): ControlProfile {
            val elements = listOf(
                ControlElement.Stick(
                    x = 0.16f, y = 0.70f, scale = 1.15f, label = "",
                    axisX = ControlAction.ABS_X, axisY = ControlAction.ABS_Y,
                ),
                ControlElement.Stick(
                    x = 0.72f, y = 0.62f, scale = 0.95f, label = "",
                    axisX = ControlAction.ABS_RX, axisY = ControlAction.ABS_RY,
                ),
                ControlElement.DPad(
                    x = 0.16f, y = 0.42f, scale = 0.9f, useHat = true,
                ),
                // Face buttons (A B X Y) — right side
                ControlElement.Button(
                    x = 0.88f, y = 0.55f, label = "A",
                    action = ControlAction.PadButton(ControlAction.BTN_A),
                ),
                ControlElement.Button(
                    x = 0.82f, y = 0.45f, label = "B",
                    action = ControlAction.PadButton(ControlAction.BTN_B),
                ),
                ControlElement.Button(
                    x = 0.82f, y = 0.65f, label = "X",
                    action = ControlAction.PadButton(ControlAction.BTN_X),
                ),
                ControlElement.Button(
                    x = 0.76f, y = 0.55f, label = "Y",
                    action = ControlAction.PadButton(ControlAction.BTN_Y),
                ),
                ControlElement.Button(
                    x = 0.70f, y = 0.28f, scale = 0.75f, label = "LB",
                    action = ControlAction.PadButton(ControlAction.BTN_TL),
                ),
                ControlElement.Button(
                    x = 0.88f, y = 0.28f, scale = 0.75f, label = "RB",
                    action = ControlAction.PadButton(ControlAction.BTN_TR),
                ),
                ControlElement.Button(
                    x = 0.42f, y = 0.22f, scale = 0.7f, label = "Select",
                    action = ControlAction.PadButton(ControlAction.BTN_SELECT),
                ),
                ControlElement.Button(
                    x = 0.58f, y = 0.22f, scale = 0.7f, label = "Start",
                    action = ControlAction.PadButton(ControlAction.BTN_START),
                ),
                ControlElement.Button(
                    x = 0.96f, y = 0.12f, scale = 0.7f, label = "",
                    action = ControlAction.Menu,
                ),
            )
            return ControlProfile("Gamepad", elements)
        }


        /**
         * Mouse + keyboard layout: free-placement keys/mouse buttons; empty screen is a trackpad
         * (relative mouse via [XInput.moveBy]).
         */
        fun defaultMouseKeyboard(): ControlProfile {
            fun key(label: String, androidKey: Int, x: Float, y: Float, scale: Float = 0.85f, sticky: Boolean = false) =
                ControlElement.Button(
                    x = x, y = y, scale = scale, opacity = 0.5f, label = label,
                    action = ControlAction.fromAndroidKey(androidKey, sticky) ?: ControlAction.None,
                    toggle = sticky,
                )
            fun mouse(label: String, btn: Int, x: Float, y: Float) =
                ControlElement.Button(
                    x = x, y = y, scale = 0.9f, opacity = 0.5f, label = label,
                    action = ControlAction.MouseButton(btn),
                )
            fun combo(label: String, androidKeys: List<Int>, x: Float, y: Float): ControlElement.Button {
                val codes = androidKeys.mapNotNull { ControlAction.fromAndroidKey(it)?.xKeycode }
                return ControlElement.Button(
                    x = x, y = y, scale = 0.75f, opacity = 0.5f, label = label,
                    action = if (codes.isEmpty()) ControlAction.None else ControlAction.KeyCombo(codes),
                )
            }
            val elements = listOf(
                // WASD cluster (left)
                key("W", android.view.KeyEvent.KEYCODE_W, 0.14f, 0.55f),
                key("A", android.view.KeyEvent.KEYCODE_A, 0.08f, 0.68f),
                key("S", android.view.KeyEvent.KEYCODE_S, 0.14f, 0.68f),
                key("D", android.view.KeyEvent.KEYCODE_D, 0.20f, 0.68f),
                key("Shift", android.view.KeyEvent.KEYCODE_SHIFT_LEFT, 0.10f, 0.82f, sticky = true),
                key("Ctrl", android.view.KeyEvent.KEYCODE_CTRL_LEFT, 0.20f, 0.82f, sticky = true),
                key("Space", android.view.KeyEvent.KEYCODE_SPACE, 0.35f, 0.88f, scale = 1.1f),
                // Right: mouse + utility
                mouse("LMB", 1, 0.82f, 0.55f),
                mouse("RMB", 3, 0.92f, 0.55f),
                mouse("MMB", 2, 0.87f, 0.42f),
                key("Esc", android.view.KeyEvent.KEYCODE_ESCAPE, 0.08f, 0.18f, scale = 0.7f),
                key("Tab", android.view.KeyEvent.KEYCODE_TAB, 0.18f, 0.18f, scale = 0.7f),
                key("Enter", android.view.KeyEvent.KEYCODE_ENTER, 0.90f, 0.78f, scale = 0.8f),
                combo("Ctrl+C", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_C), 0.78f, 0.28f),
                combo("Ctrl+V", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_V), 0.90f, 0.28f),
                ControlElement.Button(
                    x = 0.96f, y = 0.12f, scale = 0.7f, label = "",
                    action = ControlAction.Menu,
                ),
            )
            return ControlProfile("Mouse/Keyboard", elements)
        }

        /** Keyboard-like default close to OnScreenKeys.DEFAULT_*. */
        fun defaultKeys(): ControlProfile = fromSideStrips(
            OnScreenKeys.DEFAULT_LEFT,
            OnScreenKeys.DEFAULT_RIGHT,
        )
    }
}
