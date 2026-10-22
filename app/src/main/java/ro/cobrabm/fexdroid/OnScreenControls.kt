package ro.cobrabm.fexdroid

import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Free-placement on-screen controls (buttons, analog sticks, D-pads).
 *
 * Sticks, D-pads and pad buttons talk to the guest through [PadBridge] on
 * [Gamepads.onScreenSlot] (Xbox 360 layout, node opened early for Steam). Keys and mouse buttons still go through [XInput].
 *
 * Touch ownership mirrors [OnScreenKeys]: [filter] peels fingers on controls out of the
 * window event so [InputSurfaceView] only sees the rest.
 *
 * Call [loadProfile] / [Overlay] from the player when a layout is active; chain
 * [filter] in [MainActivity.dispatchTouchEvent] (after or instead of OnScreenKeys).
 */
object OnScreenControls {
    private const val TRACK_ID = "__trackpad__"
    private const val TRACK_SENS = 1.35f

    /** Slot is owned by [Gamepads.onScreenSlot] (opened early at session start). */
    private val padSlot: Int get() = Gamepads.onScreenSlot

    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val BASE_BUTTON_DP = 56f
    private const val BASE_STICK_DP = 140f
    private const val BASE_DPAD_DP = 130f

    var profile by mutableStateOf<ControlProfile?>(null); private set
    /** True while a profile with at least one visible element is loaded. */
    val active get() = profile?.elements?.any { it.visible } == true

    private var padOpen = false
    private val events = IntArray(64 * 3)

    // Sticky / toggle state (X keys and mode switches).
    private val heldKeys = mutableStateListOf<Int>()
    private var tapSecond by mutableStateOf(false)
    private var swipeDrags by mutableStateOf(false)

    /** Same meaning as [OnScreenKeys.tapIsSecondButton] when this overlay is active. */
    val tapIsSecondButton get() = active && tapSecond
    val swipeIsDrag get() = active && swipeDrags

    // —— hit testing (main thread) ——
    private class Spot(
        var area: RectF = RectF(),
        var elementId: String = "",
        var kind: Kind = Kind.Button,
    )
    private enum class Kind { Button, Stick, DPad }

    private val spots = HashMap<String, Spot>()
    /** pointerId → element id currently capturing that finger. */
    private val fingers = HashMap<Int, String>()
    private val pressedButtons = mutableStateListOf<String>()
    private var pictureDown = 0L

    // Live stick / dpad state for drawing and for lifting on reset.
    private data class StickLive(var nx: Float = 0f, var ny: Float = 0f, var pointerId: Int = -1)
    private data class DPadLive(var dx: Int = 0, var dy: Int = 0, var pointerId: Int = -1)
    private val stickLive = HashMap<String, StickLive>()
    /** Observable stick deflection for Compose (nx, ny in −1..1). */
    private val stickPos = mutableStateMapOf<String, Offset>()
    private val dpadLive = HashMap<String, DPadLive>()

    /** Fingers on empty space in MouseKeyboard mode → relative mouse (trackpad). */
    private data class TrackLive(var lastX: Float, var lastY: Float, var moved: Boolean = false)
    private val trackLive = HashMap<Int, TrackLive>()

    fun loadProfile(p: ControlProfile?) {
        reset()
        profile = p
        if (p != null && needsPad(p)) ensurePad()
    }

    private fun needsPad(p: ControlProfile): Boolean = p.elements.any { el ->
        when (el) {
            is ControlElement.Stick, is ControlElement.DPad -> true
            is ControlElement.Button -> el.action is ControlAction.PadButton || el.action is ControlAction.PadAxis
        }
    }

    private fun ensurePad() {
        // Prefer the slot reserved at Gamepads.start (usually 0). Fall back to opening one now.
        var slot = padSlot
        if (slot < 0) slot = Gamepads.ensureVirtualPad()
        if (slot < 0) return
        // open() is idempotent when the node already exists.
        if (!PadBridge.open(slot)) return
        padOpen = true
    }

    private fun closePad() {
        if (!padOpen) return
        // Zero axes only — keep the node alive so Steam does not lose the controller.
        zeroAllPadAxes()
        padOpen = false
    }

    private fun sendPad(count: Int) {
        if (count <= 0) return
        ensurePad()
        val slot = padSlot
        if (!padOpen || slot < 0) return
        PadBridge.send(slot, events, count)
        android.util.Log.d("fexdroid-osc", "send slot=$slot n=$count readers=${PadBridge.readers(slot)}")
    }

    private fun put(n: Int, type: Int, code: Int, value: Int): Int {
        events[n * 3] = type
        events[n * 3 + 1] = code
        events[n * 3 + 2] = value
        return n + 1
    }

    private fun zeroAllPadAxes() {
        var n = 0
        n = put(n, EV_ABS, ControlAction.ABS_X, 0)
        n = put(n, EV_ABS, ControlAction.ABS_Y, 0)
        n = put(n, EV_ABS, ControlAction.ABS_RX, 0)
        n = put(n, EV_ABS, ControlAction.ABS_RY, 0)
        n = put(n, EV_ABS, ControlAction.ABS_Z, 0)
        n = put(n, EV_ABS, ControlAction.ABS_RZ, 0)
        n = put(n, EV_ABS, ControlAction.ABS_HAT0X, 0)
        n = put(n, EV_ABS, ControlAction.ABS_HAT0Y, 0)
        sendPad(n)
    }

    // —— actions ——

    private fun actionDown(action: ControlAction) {
        when (action) {
            is ControlAction.Key -> {
                if (action.sticky) {
                    if (heldKeys.remove(action.xKeycode)) XInput.key(action.xKeycode, false)
                    else { heldKeys += action.xKeycode; XInput.key(action.xKeycode, true) }
                } else XInput.key(action.xKeycode, true)
            }
            is ControlAction.MouseButton -> XInput.button(action.button, true)
            is ControlAction.KeyCombo -> {
                for (k in action.xKeycodes) XInput.key(k, true)
            }
            is ControlAction.PadButton -> {
                android.util.Log.d("fexdroid-osc", "PadButton down code=0x${action.code.toString(16)}")
                sendPad(put(0, EV_KEY, action.code, 1))
            }
            is ControlAction.PadAxis -> {
                sendPad(put(0, EV_ABS, action.code, action.value))
            }
            ControlAction.ToggleTapButton -> tapSecond = !tapSecond
            ControlAction.ToggleSwipeMode -> swipeDrags = !swipeDrags
            ControlAction.Menu, ControlAction.None -> {}
        }
    }

    private fun actionUp(action: ControlAction) {
        when (action) {
            is ControlAction.Key -> if (!action.sticky) XInput.key(action.xKeycode, false)
            is ControlAction.MouseButton -> XInput.button(action.button, false)
            is ControlAction.KeyCombo -> {
                for (k in action.xKeycodes.asReversed()) XInput.key(k, false)
            }
            is ControlAction.PadButton -> sendPad(put(0, EV_KEY, action.code, 0))
            is ControlAction.PadAxis -> sendPad(put(0, EV_ABS, action.code, 0))
            else -> {}
        }
    }

    private var onMenu: (() -> Unit)? = null

    fun setMenuHandler(handler: (() -> Unit)?) { onMenu = handler }

    // —— sticks ——

    private fun stickDown(el: ControlElement.Stick, pointerId: Int, wx: Float, wy: Float, area: RectF) {
        val live = stickLive.getOrPut(el.id) { StickLive() }
        live.pointerId = pointerId
        stickMove(el, wx, wy, area)
    }

    private fun stickMove(el: ControlElement.Stick, wx: Float, wy: Float, area: RectF) {
        val cx = area.centerX()
        val cy = area.centerY()
        val radius = minOf(area.width(), area.height()) / 2f
        if (radius < 1f) return
        var dx = (wx - cx) / radius
        var dy = (wy - cy) / radius
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }

        val live = stickLive.getOrPut(el.id) { StickLive() }
        when (el.mode) {
            StickMode.Analog -> {
                val active = len >= el.deadzone
                val nx = if (active) (dx * el.sensitivity).coerceIn(-1f, 1f) else 0f
                val ny = if (active) (dy * el.sensitivity).coerceIn(-1f, 1f) else 0f
                live.nx = nx; live.ny = ny
                stickPos[el.id] = Offset(nx, ny)
                val ax = (nx * 32767f).roundToInt().coerceIn(-32768, 32767)
                val ay = (ny * 32767f).roundToInt().coerceIn(-32768, 32767)
                var n = 0
                n = put(n, EV_ABS, el.axisX, ax)
                n = put(n, EV_ABS, el.axisY, ay)
                sendPad(n)
            }
            StickMode.Digital4, StickMode.Digital8 -> {
                val (dxI, dyI) = digitalDir(dx, dy, len, el.deadzone, eight = el.mode == StickMode.Digital8)
                applyDigitalStick(el, live, dxI, dyI)
            }
        }
    }

    private fun stickUp(el: ControlElement.Stick) {
        val live = stickLive[el.id] ?: return
        live.pointerId = -1
        live.nx = 0f; live.ny = 0f
        when (el.mode) {
            StickMode.Analog -> {
                stickPos[el.id] = Offset.Zero
                var n = 0
                n = put(n, EV_ABS, el.axisX, 0)
                n = put(n, EV_ABS, el.axisY, 0)
                sendPad(n)
            }
            StickMode.Digital4, StickMode.Digital8 -> applyDigitalStick(el, live, 0, 0)
        }
    }

    private fun applyDigitalStick(el: ControlElement.Stick, live: StickLive, dx: Int, dy: Int) {
        val prevX = live.nx.roundToInt().coerceIn(-1, 1)
        val prevY = live.ny.roundToInt().coerceIn(-1, 1)
        // Reuse nx/ny as discrete -1/0/1 for drawing.
        live.nx = dx.toFloat(); live.ny = dy.toFloat()
        stickPos[el.id] = Offset(live.nx, live.ny)
        if (dx != prevX) {
            if (prevX < 0) actionUp(el.left) else if (prevX > 0) actionUp(el.right)
            if (dx < 0) actionDown(el.left) else if (dx > 0) actionDown(el.right)
        }
        if (dy != prevY) {
            if (prevY < 0) actionUp(el.up) else if (prevY > 0) actionUp(el.down)
            if (dy < 0) actionDown(el.up) else if (dy > 0) actionDown(el.down)
        }
        // Also drive the pad axes so games that only read sticks still work.
        var n = 0
        n = put(n, EV_ABS, el.axisX, dx * 32767)
        n = put(n, EV_ABS, el.axisY, dy * 32767)
        sendPad(n)
    }

    // —— d-pad ——

    private fun dpadDown(el: ControlElement.DPad, pointerId: Int, wx: Float, wy: Float, area: RectF) {
        val live = dpadLive.getOrPut(el.id) { DPadLive() }
        live.pointerId = pointerId
        dpadMove(el, wx, wy, area)
    }

    private fun dpadMove(el: ControlElement.DPad, wx: Float, wy: Float, area: RectF) {
        val cx = area.centerX()
        val cy = area.centerY()
        val radius = minOf(area.width(), area.height()) / 2f
        if (radius < 1f) return
        val dx = (wx - cx) / radius
        val dy = (wy - cy) / radius
        val len = hypot(dx, dy)
        val (ix, iy) = digitalDir(dx, dy, len, deadzone = 0.25f, eight = el.eightWay)
        applyDPad(el, ix, iy)
    }

    private fun dpadUp(el: ControlElement.DPad) {
        val live = dpadLive[el.id] ?: return
        live.pointerId = -1
        applyDPad(el, 0, 0)
    }

    private fun applyDPad(el: ControlElement.DPad, dx: Int, dy: Int) {
        val live = dpadLive.getOrPut(el.id) { DPadLive() }
        val prevX = live.dx; val prevY = live.dy
        live.dx = dx; live.dy = dy
        if (el.useHat) {
            var n = 0
            if (dx != prevX) n = put(n, EV_ABS, ControlAction.ABS_HAT0X, dx)
            if (dy != prevY) n = put(n, EV_ABS, ControlAction.ABS_HAT0Y, dy)
            sendPad(n)
        } else {
            if (dx != prevX) {
                if (prevX < 0) actionUp(el.left) else if (prevX > 0) actionUp(el.right)
                if (dx < 0) actionDown(el.left) else if (dx > 0) actionDown(el.right)
            }
            if (dy != prevY) {
                if (prevY < 0) actionUp(el.up) else if (prevY > 0) actionUp(el.down)
                if (dy < 0) actionDown(el.up) else if (dy > 0) actionDown(el.down)
            }
        }
    }

    /** Map a unit vector to (-1/0/1, -1/0/1). */
    private fun digitalDir(dx: Float, dy: Float, len: Float, deadzone: Float, eight: Boolean): Pair<Int, Int> {
        if (len < deadzone) return 0 to 0
        if (!eight) {
            return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy))
                (if (dx < 0) -1 else 1) to 0
            else
                0 to (if (dy < 0) -1 else 1)
        }
        // 8-way: 45° sectors.
        val angle = atan2(dy, dx) // -PI..PI, 0 = right
        val sector = ((angle + Math.PI) / (Math.PI / 4)).toInt().coerceIn(0, 7)
        // sector 0 = left, then counter-clockwise… easier via rounded components:
        val ix = when {
            dx > 0.38f -> 1
            dx < -0.38f -> -1
            else -> 0
        }
        val iy = when {
            dy > 0.38f -> 1
            dy < -0.38f -> -1
            else -> 0
        }
        return if (ix == 0 && iy == 0) {
            // Fallback to dominant axis near the deadzone edge.
            if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx < 0) -1 else 1) to 0
            else 0 to (if (dy < 0) -1 else 1)
        } else ix to iy
    }

    // —— buttons ——

    private fun buttonDown(el: ControlElement.Button) {
        if (el.id !in pressedButtons) pressedButtons += el.id
        if (el.action is ControlAction.Menu) {
            onMenu?.invoke()
            return
        }
        if (el.toggle) {
            // Toggle actions fire only on down (sticky key / mode switch).
            actionDown(el.action)
        } else {
            actionDown(el.action)
        }
    }

    private fun buttonUp(el: ControlElement.Button) {
        pressedButtons.remove(el.id)
        if (!el.toggle && el.action !is ControlAction.Menu) actionUp(el.action)
    }

    private fun elementById(id: String): ControlElement? =
        profile?.elements?.firstOrNull { it.id == id }

    private fun lift(finger: Int) {
        val id = fingers.remove(finger) ?: return
        if (id == TRACK_ID) {
            val live = trackLive.remove(finger)
            // Short tap without movement → left click (trackpad).
            if (live != null && !live.moved) {
                XInput.button(1, true)
                XInput.button(1, false)
            }
            return
        }
        if (id in fingers.values) return // another finger still on the same control
        when (val el = elementById(id)) {
            is ControlElement.Button -> buttonUp(el)
            is ControlElement.Stick -> stickUp(el)
            is ControlElement.DPad -> dpadUp(el)
            null -> {}
        }
    }

    /**
     * Same contract as [OnScreenKeys.filter]: returns the event for the picture, a synthetic
     * one, or null when every finger was on a control.
     */
    fun filter(e: MotionEvent): MotionEvent? {
        if (!active && fingers.isEmpty()) return e
        if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return e
        val action = e.actionMasked
        val index = e.actionIndex
        val finger = e.getPointerId(index)
        var mine = false
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) for (f in fingers.keys.toList()) lift(f)
                if (InputSurfaceView.overlayOpen) {
                    // Menus own the touch stream.
                } else {
                    val x = e.getX(index)
                    val y = e.getY(index)
                    val hit = spots.entries.firstOrNull { it.value.area.contains(x, y) }
                    if (hit != null) {
                        mine = true
                        val id = hit.key
                        val already = id in fingers.values
                        fingers[finger] = id
                        val el = elementById(id)
                        if (!already && el != null) {
                            when (el) {
                                is ControlElement.Button -> buttonDown(el)
                                is ControlElement.Stick -> stickDown(el, finger, x, y, hit.value.area)
                                is ControlElement.DPad -> dpadDown(el, finger, x, y, hit.value.area)
                            }
                        }
                    } else if (AppSettings.trackpadMode) {
                        // Empty area → trackpad (relative mouse).
                        mine = true
                        fingers[finger] = TRACK_ID
                        trackLive[finger] = TrackLive(x, y)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val id = fingers[pid] ?: continue
                    val x = e.getX(i); val y = e.getY(i)
                    if (id == TRACK_ID) {
                        val live = trackLive[pid] ?: continue
                        val dx = ((x - live.lastX) * TRACK_SENS).toInt()
                        val dy = ((y - live.lastY) * TRACK_SENS).toInt()
                        if (dx != 0 || dy != 0) {
                            XInput.moveBy(dx, dy)
                            live.moved = true
                            live.lastX = x; live.lastY = y
                        }
                        continue
                    }
                    val el = elementById(id) ?: continue
                    val spot = spots[id] ?: continue
                    when (el) {
                        is ControlElement.Stick -> stickMove(el, x, y, spot.area)
                        is ControlElement.DPad -> dpadMove(el, x, y, spot.area)
                        is ControlElement.Button -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (finger in fingers) { mine = true; lift(finger) }
            MotionEvent.ACTION_CANCEL -> {
                for (f in fingers.keys.toList()) lift(f)
                return e
            }
        }
        if (!mine && fingers.isEmpty()) return e
        val keep = (0 until e.pointerCount).filter { i ->
            !(mine && i == index) && e.getPointerId(i) !in fingers
        }
        if (keep.isEmpty()) return null
        val place = keep.indexOf(index) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val rest = when {
            mine -> MotionEvent.ACTION_MOVE
            action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ->
                if (keep.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or place
            action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ->
                if (keep.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or place
            else -> action
        }
        if (rest == MotionEvent.ACTION_DOWN) pictureDown = e.eventTime
        val props = Array(keep.size) { MotionEvent.PointerProperties().also { p -> e.getPointerProperties(keep[it], p) } }
        val coords = Array(keep.size) { MotionEvent.PointerCoords().also { c -> e.getPointerCoords(keep[it], c) } }
        return MotionEvent.obtain(
            pictureDown, e.eventTime, rest, keep.size, props, coords,
            e.metaState, e.buttonState, e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags,
        )
    }

    fun reset() {
        for (f in fingers.keys.toList()) lift(f)
        fingers.clear()
        pressedButtons.clear()
        for (k in heldKeys.toList()) XInput.key(k, false)
        heldKeys.clear()
        tapSecond = false
        swipeDrags = false
        stickLive.clear()
        stickPos.clear()
        dpadLive.clear()
        trackLive.clear()
        zeroAllPadAxes()
        closePad()
    }

    // —— Compose UI ——

    /**
     * Draws every visible element of [profile] over the player.
     * Positions are normalised (0..1); size comes from type × [ControlElement.scale].
     */
    @Composable
    fun Overlay(
        profile: ControlProfile,
        modifier: Modifier = Modifier,
        onMenu: () -> Unit = {},
    ) {
        DisposableEffect(onMenu) {
            setMenuHandler(onMenu)
            onDispose { setMenuHandler(null) }
        }
        DisposableEffect(profile) {
            loadProfile(profile)
            onDispose { if (OnScreenControls.profile === profile) loadProfile(null) }
        }
        BoxWithConstraints(modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val density = LocalDensity.current
            for (el in profile.elements) {
                if (!el.visible) continue
                when (el) {
                    is ControlElement.Button -> ButtonView(el, w, h, density.density)
                    is ControlElement.Stick -> StickView(el, w, h, density.density)
                    is ControlElement.DPad -> DPadView(el, w, h, density.density)
                }
            }
        }
    }

    @Composable
    private fun ButtonView(el: ControlElement.Button, winW: Float, winH: Float, density: Float) {
        val sizeDp = (BASE_BUTTON_DP * el.scale).dp
        val sizePx = BASE_BUTTON_DP * el.scale * density
        val view = LocalView.current
        val pressed = el.id in pressedButtons
        val lit = when (val a = el.action) {
            is ControlAction.Key -> a.sticky && a.xKeycode in heldKeys
            ControlAction.ToggleTapButton -> tapSecond
            ControlAction.ToggleSwipeMode -> swipeDrags
            else -> false
        }
        val spot = remember(el.id) { Spot(kind = Kind.Button, elementId = el.id) }
        DisposableEffect(el.id) {
            spots[el.id] = spot
            onDispose { if (spots[el.id] === spot) spots.remove(el.id) }
        }
        val fill = when {
            pressed -> Color.White.copy(alpha = 0.45f)
            lit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = el.opacity * 0.35f)
        }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (el.x * winW - sizePx / 2f).roundToInt(),
                        (el.y * winH - sizePx / 2f).roundToInt(),
                    )
                }
                .size(sizeDp)
                .background(fill, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    spot.area = RectF(b.left, b.top, b.right, b.bottom)
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                el.action is ControlAction.Menu || el.label.isEmpty() && el.action is ControlAction.Menu ->
                    Icon(AppIcons.Menu, null, tint = Color.White.copy(alpha = 0.9f))
                el.action is ControlAction.Menu ->
                    Icon(AppIcons.Menu, null, tint = Color.White.copy(alpha = 0.9f))
                el.label.isNotEmpty() -> Text(
                    el.label,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = if (el.label.length > 2) 11.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        // Haptic is triggered from buttonDown; LocalView kept for future use.
        DisposableEffect(view) { onDispose { } }
    }

    @Composable
    private fun StickView(el: ControlElement.Stick, winW: Float, winH: Float, density: Float) {
        val sizeDp = (BASE_STICK_DP * el.scale).dp
        val sizePx = BASE_STICK_DP * el.scale * density
        // stickPos is observable → Canvas recomposes when the stick moves.
        val pos = stickPos[el.id] ?: Offset.Zero
        val active = pos.getDistance() > 0.01f
        val knobN = 0.42f
        val spot = remember(el.id) { Spot(kind = Kind.Stick, elementId = el.id) }
        DisposableEffect(el.id) {
            spots[el.id] = spot
            onDispose { if (spots[el.id] === spot) spots.remove(el.id) }
        }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (el.x * winW - sizePx / 2f).roundToInt(),
                        (el.y * winH - sizePx / 2f).roundToInt(),
                    )
                }
                .size(sizeDp)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    spot.area = RectF(b.left, b.top, b.right, b.bottom)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val stroke = 2.dp.toPx()
                val baseA = el.opacity * if (active) 0.28f else 0.18f
                drawCircle(color = Color.White.copy(alpha = baseA), radius = r)
                drawCircle(
                    color = Color.White.copy(alpha = el.opacity * 0.5f),
                    radius = r,
                    style = Stroke(width = stroke),
                )
                // Guide ring at deadzone edge
                val dz = el.deadzone.coerceIn(0f, 0.5f)
                if (dz > 0.02f) {
                    drawCircle(
                        color = Color.White.copy(alpha = el.opacity * 0.15f),
                        radius = r * (1f - knobN) * dz + r * knobN,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                val travel = r * (1f - knobN)
                val kx = center.x + pos.x * travel
                val ky = center.y + pos.y * travel
                // Stem from center to knob
                if (active) {
                    drawLine(
                        color = Color.White.copy(alpha = el.opacity * 0.35f),
                        start = center,
                        end = Offset(kx, ky),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                val knobR = r * knobN
                drawCircle(
                    color = Color.White.copy(alpha = if (active) 0.75f else el.opacity * 0.55f),
                    radius = knobR,
                    center = Offset(kx, ky),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.65f),
                    radius = knobR,
                    center = Offset(kx, ky),
                    style = Stroke(width = stroke),
                )
            }
        }
    }

    @Composable
    private fun DPadView(el: ControlElement.DPad, winW: Float, winH: Float, density: Float) {
        val sizeDp = (BASE_DPAD_DP * el.scale).dp
        val sizePx = BASE_DPAD_DP * el.scale * density
        val live = dpadLive[el.id]
        val spot = remember(el.id) { Spot(kind = Kind.DPad, elementId = el.id) }
        DisposableEffect(el.id) {
            spots[el.id] = spot
            onDispose { if (spots[el.id] === spot) spots.remove(el.id) }
        }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (el.x * winW - sizePx / 2f).roundToInt(),
                        (el.y * winH - sizePx / 2f).roundToInt(),
                    )
                }
                .size(sizeDp)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    spot.area = RectF(b.left, b.top, b.right, b.bottom)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val arm = r * 0.38f
                val gap = r * 0.12f
                val stroke = 2.dp.toPx()
                drawCircle(Color.White.copy(alpha = el.opacity * 0.12f), radius = r)
                drawCircle(Color.White.copy(alpha = el.opacity * 0.4f), radius = r, style = Stroke(stroke))
                // Four arms; highlight the active direction.
                fun armColor(active: Boolean) =
                    if (active) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = el.opacity * 0.35f)
                val dx = live?.dx ?: 0
                val dy = live?.dy ?: 0
                // up
                drawRect(
                    armColor(dy < 0),
                    topLeft = Offset(center.x - arm / 2, center.y - r + gap),
                    size = androidx.compose.ui.geometry.Size(arm, r - gap - arm / 2),
                )
                // down
                drawRect(
                    armColor(dy > 0),
                    topLeft = Offset(center.x - arm / 2, center.y + arm / 2),
                    size = androidx.compose.ui.geometry.Size(arm, r - gap - arm / 2),
                )
                // left
                drawRect(
                    armColor(dx < 0),
                    topLeft = Offset(center.x - r + gap, center.y - arm / 2),
                    size = androidx.compose.ui.geometry.Size(r - gap - arm / 2, arm),
                )
                // right
                drawRect(
                    armColor(dx > 0),
                    topLeft = Offset(center.x + arm / 2, center.y - arm / 2),
                    size = androidx.compose.ui.geometry.Size(r - gap - arm / 2, arm),
                )
                // center hub
                drawCircle(Color.White.copy(alpha = el.opacity * 0.4f), radius = arm * 0.55f)
            }
        }
    }
}
