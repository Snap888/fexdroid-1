package ro.cobrabm.fexdroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Kind of on-screen layout (matches [OnScreenControlMode] Gamepad / MouseKeyboard). */
enum class ProfileKind {
    Gamepad,
    MouseKeyboard,
}

/**
 * One selectable layout entry: built-in template or user-saved copy.
 * Built-ins cannot be deleted; editing a built-in saves a user profile.
 */
data class ControlProfileEntry(
    val id: String,
    val name: String,
    val kind: ProfileKind,
    val builtIn: Boolean,
    /** Layout body (name field mirrors [name] for display). */
    val layout: ControlProfile,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("kind", kind.name)
        .put("builtIn", builtIn)
        .put("layout", layout.toJson())

    companion object {
        fun fromJson(o: JSONObject): ControlProfileEntry? {
            val id = o.optString("id", "").ifBlank { return null }
            val kind = runCatching { ProfileKind.valueOf(o.optString("kind", "Gamepad")) }
                .getOrDefault(ProfileKind.Gamepad)
            val layoutObj = o.optJSONObject("layout") ?: return null
            val layout = ControlProfile.fromJson(layoutObj)
            return ControlProfileEntry(
                id = id,
                name = o.optString("name", layout.name).ifBlank { layout.name },
                kind = kind,
                builtIn = o.optBoolean("builtIn", false),
                layout = layout.copy(name = o.optString("name", layout.name).ifBlank { layout.name }),
            )
        }
    }
}

/**
 * Built-in gamepad templates and helpers to build default mouse/keyboard layout.
 * Virtual pad is always Xbox 360 codes via [PadBridge]; DualShock / Steam Pad differ by placement and labels.
 */
object ControlProfiles {

    const val ID_XBOX360 = "builtin_xbox360"
    const val ID_DUALSHOCK = "builtin_dualshock"
    const val ID_STEAMPAD = "builtin_steampad"
    const val ID_MK_DEFAULT = "builtin_mk_default"

    fun builtIns(): List<ControlProfileEntry> = listOf(
        ControlProfileEntry(ID_XBOX360, "Xbox 360", ProfileKind.Gamepad, true, xbox360()),
        ControlProfileEntry(ID_DUALSHOCK, "DualShock", ProfileKind.Gamepad, true, dualShock()),
        ControlProfileEntry(ID_STEAMPAD, "Steam Pad", ProfileKind.Gamepad, true, steamPad()),
        ControlProfileEntry(ID_MK_DEFAULT, "Mouse / Keyboard", ProfileKind.MouseKeyboard, true, mouseKeyboard()),
    )

    fun xbox360(): ControlProfile = ControlProfile.defaultGamepad().copy(name = "Xbox 360")

    /** Same pad codes as Xbox; PlayStation-style labels and slightly different placement. */
    fun dualShock(): ControlProfile {
        val elements = listOf(
            ControlElement.Stick(
                x = 0.18f, y = 0.68f, scale = 1.1f, label = "",
                axisX = ControlAction.ABS_X, axisY = ControlAction.ABS_Y,
            ),
            ControlElement.Stick(
                x = 0.62f, y = 0.68f, scale = 1.1f, label = "",
                axisX = ControlAction.ABS_RX, axisY = ControlAction.ABS_RY,
            ),
            ControlElement.DPad(x = 0.18f, y = 0.40f, scale = 0.95f, useHat = true),
            ControlElement.Button(x = 0.88f, y = 0.52f, label = "×", action = ControlAction.PadButton(ControlAction.BTN_A)),
            ControlElement.Button(x = 0.82f, y = 0.42f, label = "○", action = ControlAction.PadButton(ControlAction.BTN_B)),
            ControlElement.Button(x = 0.82f, y = 0.62f, label = "□", action = ControlAction.PadButton(ControlAction.BTN_X)),
            ControlElement.Button(x = 0.76f, y = 0.52f, label = "△", action = ControlAction.PadButton(ControlAction.BTN_Y)),
            ControlElement.Button(x = 0.70f, y = 0.26f, scale = 0.75f, label = "L1", action = ControlAction.PadButton(ControlAction.BTN_TL)),
            ControlElement.Button(x = 0.88f, y = 0.26f, scale = 0.75f, label = "R1", action = ControlAction.PadButton(ControlAction.BTN_TR)),
            ControlElement.Button(x = 0.40f, y = 0.22f, scale = 0.7f, label = "Share", action = ControlAction.PadButton(ControlAction.BTN_SELECT)),
            ControlElement.Button(x = 0.60f, y = 0.22f, scale = 0.7f, label = "Options", action = ControlAction.PadButton(ControlAction.BTN_START)),
            ControlElement.Button(x = 0.96f, y = 0.12f, scale = 0.7f, label = "", action = ControlAction.Menu),
        )
        return ControlProfile("DualShock", elements)
    }

    /** Steam Controller-inspired: big right pad zone as stick, more face buttons. */
    fun steamPad(): ControlProfile {
        val elements = listOf(
            ControlElement.Stick(
                x = 0.14f, y = 0.55f, scale = 1.2f, label = "",
                axisX = ControlAction.ABS_X, axisY = ControlAction.ABS_Y,
            ),
            ControlElement.Stick(
                x = 0.78f, y = 0.58f, scale = 1.25f, label = "",
                axisX = ControlAction.ABS_RX, axisY = ControlAction.ABS_RY,
            ),
            ControlElement.DPad(x = 0.14f, y = 0.32f, scale = 0.85f, useHat = true),
            ControlElement.Button(x = 0.55f, y = 0.72f, label = "A", action = ControlAction.PadButton(ControlAction.BTN_A)),
            ControlElement.Button(x = 0.48f, y = 0.62f, label = "B", action = ControlAction.PadButton(ControlAction.BTN_B)),
            ControlElement.Button(x = 0.48f, y = 0.82f, label = "X", action = ControlAction.PadButton(ControlAction.BTN_X)),
            ControlElement.Button(x = 0.41f, y = 0.72f, label = "Y", action = ControlAction.PadButton(ControlAction.BTN_Y)),
            ControlElement.Button(x = 0.28f, y = 0.22f, scale = 0.7f, label = "L", action = ControlAction.PadButton(ControlAction.BTN_TL)),
            ControlElement.Button(x = 0.72f, y = 0.22f, scale = 0.7f, label = "R", action = ControlAction.PadButton(ControlAction.BTN_TR)),
            ControlElement.Button(x = 0.42f, y = 0.28f, scale = 0.65f, label = "←", action = ControlAction.PadButton(ControlAction.BTN_SELECT)),
            ControlElement.Button(x = 0.58f, y = 0.28f, scale = 0.65f, label = "→", action = ControlAction.PadButton(ControlAction.BTN_START)),
            ControlElement.Button(x = 0.96f, y = 0.12f, scale = 0.7f, label = "", action = ControlAction.Menu),
        )
        return ControlProfile("Steam Pad", elements)
    }

    fun mouseKeyboard(): ControlProfile =
        ControlProfile.defaultMouseKeyboard().copy(name = "Mouse / Keyboard")

    fun newUserId(): String = "user_${UUID.randomUUID().toString().take(8)}"
}
