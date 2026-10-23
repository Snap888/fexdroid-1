package ro.cobrabm.fexdroid

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen size of the virtual X display (Xvfb). */
data class Resolution(val width: Int, val height: Int) {
    val label get() = "${width}×$height"
    companion object {
        // 540p: for phones that throttle when hot. Less work for the GPU and for the copies of
        // the picture (Mesa to Xvfb to the screen), so less heat.
        val PRESETS = listOf(Resolution(960, 540), Resolution(1280, 720), Resolution(1600, 900), Resolution(1920, 1080))
        val DEFAULT = PRESETS[1]

        /**
         * [base] widened or narrowed to the shape of the phone's screen in landscape, so the
         * picture fills it without black bars (20:9 phone: 1280x720 becomes 1600x720). The
         * height, which sets the rendering cost class, stays. Shapes are limited to 4:3..21:9.
         */
        fun fitted(ctx: Context, base: Resolution, marginPercent: Int = 0, wideSides: Boolean = false, keysDp: Int = 0): Resolution {
            val wm = ctx.getSystemService(android.view.WindowManager::class.java) ?: return base
            val (a, b) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
            if (a <= 0 || b <= 0) return base
            // The picture is shown inside a margin on every side (AppSettings.screenMargin).
            val margin = 1.0 * minOf(a, b) * marginPercent / 100
            val density = ctx.resources.displayMetrics.density.toDouble()
            // On-screen keys stand between the edge's free strip and the picture.
            val side = (if (wideSides) maxOf(margin, AppSettings.WIDE_SIDE_DP * density) else margin) + keysDp * density
            val aspect = ((maxOf(a, b) - 2 * side) / (minOf(a, b) - 2 * margin)).coerceIn(4.0 / 3.0, 21.0 / 9.0)
            val width = (Math.round(base.height * aspect / 8.0) * 8).toInt()
            return Resolution(width, base.height)
        }
    }
}

enum class ThemeMode { DARK, SYSTEM, LIGHT }

/** How the player draws touch controls over the game picture. */
enum class OnScreenControlMode {
    /** No overlay; only the floating menu button. */
    Off,
    /** Side strips of keys ([OnScreenKeys]). */
    Keys,
    /** Free-placement virtual Xbox pad ([ControlProfile.defaultGamepad]). */
    Gamepad,
    /**
     * Free-placement keyboard/mouse buttons + key chords.
     * Empty areas of the screen act as a trackpad ([XInput.moveBy]).
     * Saved layout in [AppSettings.customControlProfile] (legacy key name).
     */
    MouseKeyboard,
}

/**
 * User settings, persisted in SharedPreferences and exposed as Compose state.
 * Call [init] once (MainActivity.onCreate) before reading.
 */
object AppSettings {
    val FPS_PRESETS = listOf(30, 45, 60)
    /** Percent of the screen's short side left free on every side of the picture. */
    val MARGIN_PRESETS = listOf(0, 3, 5, 8)
    /**
     * Width kept free at the left and right edges with [wideSides]. realme/OPPO/OnePlus put a
     * system overlay for their game tools on the top half of the left edge, 24 dp wide
     * (GamesFloatBar): touches and the mouse never reach what a game shows under it.
     */
    const val WIDE_SIDE_DP = 28

    private lateinit var prefs: SharedPreferences

    var resolution by mutableStateOf(Resolution.DEFAULT); private set
    /** Virtual screen follows the shape of the phone's screen ([Resolution.fitted]). */
    var fitScreen by mutableStateOf(true); private set
    /**
     * The picture stays this far from the screen's edges. A phone's rounded corners and camera
     * hole cover what a game draws there, and touches right at the edge are often dropped
     * (edge rejection, game modes): Dota 2 has its menu buttons and network figures there.
     */
    var screenMargin by mutableIntStateOf(3); private set
    /** The picture keeps [WIDE_SIDE_DP] free at the left and right edges of the screen. */
    var wideSides by mutableStateOf(false); private set
    var fps by mutableIntStateOf(60); private set
    var dynamicColor by mutableStateOf(true); private set
    var theme by mutableStateOf(ThemeMode.DARK); private set
    /** Home screen: Dota 2 direct start (no Steam client) as the main action. */
    var gameWithoutSteam by mutableStateOf(false); private set
    /** Steam starts in Big Picture (-gamepadui): full screen, made for touch and controllers. */
    var steamBigPicture by mutableStateOf(true); private set
    /**
     * The GPU runs at its highest frequency while a game draws (TU_KGSL_PWR_CONSTRAINT=max,
     * Mesa patch 0006). Faster frames, a warmer phone.
     */
    var gpuMaxFrequency by mutableStateOf(true); private set
    /** The busiest thread of a game gets the fastest core for itself (ThreadTuner). */
    var language by mutableStateOf(Language.SYSTEM); private set
    var threadPlacement by mutableStateOf(true); private set
    /** Games are told of less video memory than Mesa would report (MemoryWatch.videoMemoryShare). */
    var limitVideoMemory by mutableStateOf(false); private set
    /** A game's frames come straight from Mesa, not through the X server (tools/fxpresent). */
    var directFrames by mutableStateOf(false); private set
    /** Bluetooth/USB gamepads reach Steam and the games as Xbox 360 pads (Gamepads, NOTES N-054). */
    var gamepads by mutableStateOf(true); private set
    /** Source 2 games' own libraries run without TSO emulation (FexConfig.SOURCE2_WITHOUT_TSO). */
    var source2WithoutTso by mutableStateOf(false); private set
    /**
     * Touch overlay mode. [onScreenKeys] stays as a derived flag for older call sites
     * (true only in [OnScreenControlMode.Keys]).
     */
    var controlMode by mutableStateOf(OnScreenControlMode.Off); private set
    /** True when [controlMode] is [OnScreenControlMode.Keys] (side strips). */
    val onScreenKeys get() = controlMode == OnScreenControlMode.Keys
    var keysLeft by mutableStateOf(OnScreenKeys.DEFAULT_LEFT); private set
    var keysRight by mutableStateOf(OnScreenKeys.DEFAULT_RIGHT); private set
    /**
     * Legacy single-layout JSON (migrated into [userProfiles] on first load).
     * Still written when saving so older builds can read something.
     */
    var customControlProfile by mutableStateOf(""); private set

    /** User-created / edited profiles (JSON array of [ControlProfileEntry]). */
    var userProfilesJson by mutableStateOf("[]"); private set

    /** Active layout id for Gamepad mode (built-in or user). */
    var activeGamepadProfileId by mutableStateOf(ControlProfiles.ID_XBOX360); private set

    /** Active layout id for Mouse/Keyboard mode. */
    var activeMkProfileId by mutableStateOf(ControlProfiles.ID_MK_DEFAULT); private set

    /**
     * When true and mode is Mouse/Keyboard, empty screen area moves the mouse (trackpad).
     * Independent of the selected MK profile.
     */
    var trackpadEnabled by mutableStateOf(true); private set
    /** Dota 2's video settings set to the cheapest values before a session starts (DotaProfile). */
    var dotaPerformance by mutableStateOf(false); private set
    /** FEX speed/accuracy trade-off (FexConfig), written before every game start. */
    var fexProfile by mutableStateOf(FexProfile.BALANCED); private set
    /**
     * FEX JIT disk cache: faster loading after the first start of a game. Off by default:
     * with FEX-2609 it makes Steam's runtime setup.sh segfault (NOTES N-028). New pref key so
     * the old default (on) does not stick.
     */
    var fexDiskCache by mutableStateOf(false); private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        resolution = Resolution(
            prefs.getInt("x_width", Resolution.DEFAULT.width),
            prefs.getInt("x_height", Resolution.DEFAULT.height),
        )
        language = runCatching { Language.valueOf(prefs.getString("language", null) ?: "SYSTEM") }.getOrDefault(Language.SYSTEM)
        fitScreen = prefs.getBoolean("fit_screen", true)
        screenMargin = prefs.getInt("screen_margin", 3)
        wideSides = prefs.getBoolean("wide_sides",
            android.os.Build.MANUFACTURER.lowercase() in setOf("realme", "oppo", "oneplus"))
        fps = prefs.getInt("fps", 60)
        dynamicColor = prefs.getBoolean("dynamic_color", true)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        gameWithoutSteam = prefs.getBoolean("game_without_steam", false)
        steamBigPicture = prefs.getBoolean("steam_big_picture", true)
        dotaPerformance = prefs.getBoolean("dota_performance", false)
        gpuMaxFrequency = prefs.getBoolean("gpu_max_frequency", true)
        threadPlacement = prefs.getBoolean("thread_placement", true)
        // Off unless chosen: what it saves is small, and a game that needs more than the limit ends.
        limitVideoMemory = prefs.getBoolean("limit_video_memory", false)
        directFrames = prefs.getBoolean("direct_frames", false)
        gamepads = prefs.getBoolean("gamepads", true)
        source2WithoutTso = prefs.getBoolean("source2_without_tso", false)
        fexProfile = runCatching { FexProfile.valueOf(prefs.getString("fex_profile", null) ?: "BALANCED") }
            .getOrDefault(FexProfile.BALANCED)
        fexDiskCache = prefs.getBoolean("fex_disk_cache_v2", false)
        keysLeft = prefs.getString("keys_left", null) ?: OnScreenKeys.DEFAULT_LEFT
        keysRight = prefs.getString("keys_right", null) ?: OnScreenKeys.DEFAULT_RIGHT
        customControlProfile = prefs.getString("control_profile_custom", null) ?: ""
        userProfilesJson = prefs.getString("control_profiles_user", null) ?: "[]"
        activeGamepadProfileId = prefs.getString("control_profile_gamepad_id", null)
            ?: ControlProfiles.ID_XBOX360
        activeMkProfileId = prefs.getString("control_profile_mk_id", null)
            ?: ControlProfiles.ID_MK_DEFAULT
        trackpadEnabled = prefs.getBoolean("trackpad_enabled", true)
        migrateLegacyProfileIfNeeded()
        // Prefer the new key; fall back to the old boolean so existing installs keep Keys on.
        controlMode = prefs.getString("control_mode", null)?.let { raw ->
            when (raw) {
                "Custom" -> OnScreenControlMode.MouseKeyboard // renamed
                else -> runCatching { OnScreenControlMode.valueOf(raw) }.getOrNull()
            }
        } ?: if (prefs.getBoolean("on_screen_keys", false)) OnScreenControlMode.Keys else OnScreenControlMode.Off
    }

    fun updateResolution(r: Resolution) {
        resolution = r
        prefs.edit().putInt("x_width", r.width).putInt("x_height", r.height).apply()
    }

    fun updateFitScreen(v: Boolean) { fitScreen = v; prefs.edit().putBoolean("fit_screen", v).apply() }
    /** The X screen size to start a session with. */
    fun updateScreenMargin(v: Int) { screenMargin = v; prefs.edit().putInt("screen_margin", v).apply() }
    fun updateWideSides(v: Boolean) { wideSides = v; prefs.edit().putBoolean("wide_sides", v).apply() }
    fun sessionResolution(ctx: Context) =
        if (fitScreen) Resolution.fitted(ctx, resolution, screenMargin, wideSides, keysDp) else resolution

    /** Width the side-strip keys take at each side of the picture (only in Keys mode). */
    val keysDp get() = if (controlMode == OnScreenControlMode.Keys) OnScreenKeys.SIDE_DP else 0

    fun updateControlMode(v: OnScreenControlMode) {
        if (v != OnScreenControlMode.Keys) OnScreenKeys.reset()
        if (v != OnScreenControlMode.Gamepad && v != OnScreenControlMode.MouseKeyboard) OnScreenControls.reset()
        controlMode = v
        prefs.edit()
            .putString("control_mode", v.name)
            .putBoolean("on_screen_keys", v == OnScreenControlMode.Keys) // keep old key in sync
            .apply()
    }

    /** Legacy toggle used by the in-game menu: Off ↔ previous non-Off mode (default Keys). */
    fun updateOnScreenKeys(v: Boolean) {
        if (v) {
            val next = if (controlMode == OnScreenControlMode.Off) OnScreenControlMode.Keys else controlMode
            updateControlMode(if (next == OnScreenControlMode.Off) OnScreenControlMode.Keys else next)
        } else {
            updateControlMode(OnScreenControlMode.Off)
        }
    }

    fun cycleControlMode() {
        val order = OnScreenControlMode.entries
        val i = order.indexOf(controlMode)
        updateControlMode(order[(i + 1) % order.size])
    }

    fun updateKeysLeft(v: String) { keysLeft = v; prefs.edit().putString("keys_left", v).apply() }
    fun updateKeysRight(v: String) { keysRight = v; prefs.edit().putString("keys_right", v).apply() }

    fun updateCustomControlProfile(json: String) {
        customControlProfile = json
        prefs.edit().putString("control_profile_custom", json).apply()
    }

    fun updateTrackpadEnabled(v: Boolean) {
        trackpadEnabled = v
        prefs.edit().putBoolean("trackpad_enabled", v).apply()
    }

    fun toggleTrackpad() = updateTrackpadEnabled(!trackpadEnabled)

    private fun persistUserProfiles(json: String) {
        userProfilesJson = json
        prefs.edit().putString("control_profiles_user", json).apply()
    }

    private fun persistActiveIds() {
        prefs.edit()
            .putString("control_profile_gamepad_id", activeGamepadProfileId)
            .putString("control_profile_mk_id", activeMkProfileId)
            .apply()
    }

    /** One-time: old single custom JSON → user profile. */
    private fun migrateLegacyProfileIfNeeded() {
        if (customControlProfile.isBlank()) return
        val users = loadUserEntries()
        if (users.isNotEmpty()) return
        val layout = ControlProfile.fromJsonString(customControlProfile) ?: return
        if (layout.elements.isEmpty()) return
        val kind = if (controlMode == OnScreenControlMode.MouseKeyboard) ProfileKind.MouseKeyboard
        else ProfileKind.Gamepad
        val entry = ControlProfileEntry(
            id = ControlProfiles.newUserId(),
            name = layout.name.ifBlank { "Custom" },
            kind = kind,
            builtIn = false,
            layout = layout,
        )
        saveUserEntries(listOf(entry))
        if (kind == ProfileKind.Gamepad) {
            activeGamepadProfileId = entry.id
        } else {
            activeMkProfileId = entry.id
        }
        persistActiveIds()
    }

    private fun loadUserEntries(): List<ControlProfileEntry> {
        val arr = runCatching { org.json.JSONArray(userProfilesJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i)?.let { ControlProfileEntry.fromJson(it) }
                if (e != null) add(e)
            }
        }
    }

    private fun saveUserEntries(list: List<ControlProfileEntry>) {
        val arr = org.json.JSONArray()
        for (e in list) arr.put(e.toJson())
        persistUserProfiles(arr.toString())
    }

    /** Built-ins + user profiles of the given kind. */
    fun profilesFor(kind: ProfileKind): List<ControlProfileEntry> {
        val built = ControlProfiles.builtIns().filter { it.kind == kind }
        val users = loadUserEntries().filter { it.kind == kind }
        return built + users
    }

    fun profilesForCurrentMode(): List<ControlProfileEntry> = when (controlMode) {
        OnScreenControlMode.Gamepad -> profilesFor(ProfileKind.Gamepad)
        OnScreenControlMode.MouseKeyboard -> profilesFor(ProfileKind.MouseKeyboard)
        else -> emptyList()
    }

    fun activeProfileId(): String = when (controlMode) {
        OnScreenControlMode.Gamepad -> activeGamepadProfileId
        OnScreenControlMode.MouseKeyboard -> activeMkProfileId
        else -> ""
    }

    fun findProfile(id: String): ControlProfileEntry? =
        ControlProfiles.builtIns().firstOrNull { it.id == id }
            ?: loadUserEntries().firstOrNull { it.id == id }

    fun selectProfile(id: String) {
        val entry = findProfile(id) ?: return
        when (entry.kind) {
            ProfileKind.Gamepad -> {
                activeGamepadProfileId = id
                if (controlMode != OnScreenControlMode.Gamepad) {
                    updateControlMode(OnScreenControlMode.Gamepad)
                }
            }
            ProfileKind.MouseKeyboard -> {
                activeMkProfileId = id
                if (controlMode != OnScreenControlMode.MouseKeyboard) {
                    updateControlMode(OnScreenControlMode.MouseKeyboard)
                }
            }
        }
        persistActiveIds()
        // Keep legacy key in sync for debugging / old code paths.
        updateCustomControlProfile(entry.layout.toJsonString())
    }

    /**
     * Save layout from the editor.
     * Built-in → new user profile (or update existing user with same name).
     * User → overwrite.
     */
    fun saveEditedProfile(layout: ControlProfile, baseId: String?): ControlProfileEntry {
        val kind = when (controlMode) {
            OnScreenControlMode.MouseKeyboard -> ProfileKind.MouseKeyboard
            else -> ProfileKind.Gamepad
        }
        val users = loadUserEntries().toMutableList()
        val base = baseId?.let { findProfile(it) }
        val entry = if (base != null && !base.builtIn) {
            val updated = base.copy(name = layout.name.ifBlank { base.name }, layout = layout.copy(name = layout.name.ifBlank { base.name }))
            val i = users.indexOfFirst { it.id == base.id }
            if (i >= 0) users[i] = updated else users += updated
            updated
        } else {
            val name = layout.name.ifBlank {
                when {
                    base != null -> "${base.name} (custom)"
                    kind == ProfileKind.MouseKeyboard -> "Custom M/K"
                    else -> "Custom Gamepad"
                }
            }
            val created = ControlProfileEntry(
                id = ControlProfiles.newUserId(),
                name = name,
                kind = kind,
                builtIn = false,
                layout = layout.copy(name = name),
            )
            users += created
            created
        }
        saveUserEntries(users)
        selectProfile(entry.id)
        return entry
    }

    fun deleteUserProfile(id: String) {
        val users = loadUserEntries().filter { it.id != id }
        saveUserEntries(users)
        if (activeGamepadProfileId == id) {
            activeGamepadProfileId = ControlProfiles.ID_XBOX360
        }
        if (activeMkProfileId == id) {
            activeMkProfileId = ControlProfiles.ID_MK_DEFAULT
        }
        persistActiveIds()
    }

    fun createMkProfile(name: String = "New layout"): ControlProfileEntry {
        val layout = ControlProfiles.mouseKeyboard().copy(name = name)
        return saveEditedProfile(layout, baseId = null)
    }

    fun resetActiveProfileToBuiltin() {
        when (controlMode) {
            OnScreenControlMode.Gamepad -> selectProfile(ControlProfiles.ID_XBOX360)
            OnScreenControlMode.MouseKeyboard -> selectProfile(ControlProfiles.ID_MK_DEFAULT)
            else -> {}
        }
    }

    fun resetCustomControlProfile() = resetActiveProfileToBuiltin()

    /** Profile to draw for the current mode, or null when Off / Keys. */
    fun activeControlProfile(): ControlProfile? = when (controlMode) {
        OnScreenControlMode.Off, OnScreenControlMode.Keys -> null
        OnScreenControlMode.Gamepad -> {
            val e = findProfile(activeGamepadProfileId)
            e?.layout ?: ControlProfiles.xbox360()
        }
        OnScreenControlMode.MouseKeyboard -> {
            val e = findProfile(activeMkProfileId)
            e?.layout ?: ControlProfiles.mouseKeyboard()
        }
    }

    /** True when empty screen area should move the mouse relatively (trackpad). */
    val trackpadMode get() = controlMode == OnScreenControlMode.MouseKeyboard && trackpadEnabled

    fun updateLanguage(v: Language) { language = v; prefs.edit().putString("language", v.name).apply() }
    fun updateFps(v: Int) { fps = v; prefs.edit().putInt("fps", v).apply() }
    fun updateDynamicColor(v: Boolean) { dynamicColor = v; prefs.edit().putBoolean("dynamic_color", v).apply() }
    fun updateTheme(v: ThemeMode) { theme = v; prefs.edit().putString("theme", v.name).apply() }
    fun updateFexProfile(v: FexProfile) { fexProfile = v; prefs.edit().putString("fex_profile", v.name).apply() }
    fun updateFexDiskCache(v: Boolean) { fexDiskCache = v; prefs.edit().putBoolean("fex_disk_cache_v2", v).apply() }
    fun updateSteamBigPicture(v: Boolean) { steamBigPicture = v; prefs.edit().putBoolean("steam_big_picture", v).apply() }
    fun updateSource2WithoutTso(v: Boolean) { source2WithoutTso = v; prefs.edit().putBoolean("source2_without_tso", v).apply() }
    fun updateDirectFrames(v: Boolean) { directFrames = v; prefs.edit().putBoolean("direct_frames", v).apply() }
    fun updateGamepads(v: Boolean) { gamepads = v; prefs.edit().putBoolean("gamepads", v).apply() }
    fun updateLimitVideoMemory(v: Boolean) { limitVideoMemory = v; prefs.edit().putBoolean("limit_video_memory", v).apply() }
    fun updateThreadPlacement(v: Boolean) { threadPlacement = v; prefs.edit().putBoolean("thread_placement", v).apply() }
    fun updateGpuMaxFrequency(v: Boolean) { gpuMaxFrequency = v; prefs.edit().putBoolean("gpu_max_frequency", v).apply() }
    fun updateDotaPerformance(v: Boolean) { dotaPerformance = v; prefs.edit().putBoolean("dota_performance", v).apply() }
    fun updateGameWithoutSteam(v: Boolean) { gameWithoutSteam = v; prefs.edit().putBoolean("game_without_steam", v).apply() }
}
