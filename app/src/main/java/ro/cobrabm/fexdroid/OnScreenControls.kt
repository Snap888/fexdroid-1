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
 * [VIRTUAL_SLOT] (Xbox 360 layout). Keys and mouse buttons still go through [XInput].
 *
 * Touch ownership mirrors [OnScreenKeys]: [filter] peels fingers on controls out of the
 * window event so [InputSurfaceView] only sees the rest.
 *
 * Call [loadProfile] / [Overlay] from the player when a layout is active; chain
 * [filter] in [MainActivity.dispatchTouchEvent] (after or instead of OnScreenKeys).
 */
object OnScreenControls {
    /** Reserved pad slot so physical controllers keep 0..2 (Gamepads finds the first free). */
    const val VIRTUAL_SLOT = 3

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
    private val dpadLive = HashMap<String, DPadLive>()

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
        if (padOpen) return
        if (PadBridge.open(VIRTUAL_SLOT)) {
            padOpen = true
        }
    }

    private fun closePad() {
        if (!padOpen) return
        zeroAllPadAxes()
        PadBridge.close(VIRTUAL_SLOT)
        padOpen = false
    }

    private fun sendPad(count: Int) {
        if (count <= 0) return
        ensurePad()
        if (padOpen) PadBridge.send(VIRTUAL_SLOT, events, count)
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

    private fun actionDown(action: ControlAction) {
        when (action) {
            is ControlAction.Key -> {
                if (action.sticky) {
                    if (heldKeys.remove(action.xKeycode)) XInput.key(action.xKeycode, false)
                    else { heldKeys += action.xKeycode; XInput.key(action.xKeycode, true) }
                } else XInput.key(action.xKeycode, true)
            }
            is ControlAction.MouseButton -> XInput.button(action.button, true)
            is ControlAction.PadButton -> {
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
            is ControlAction.PadButton -> sendPad(put(0, EV_KEY, action.code, 0))
            is ControlAction.PadAxis -> sendPad(put(0, EV_ABS, action.code, 0))
            else -> {}
        }
    }

    private var onMenu: (() -> Unit)? = null

    fun setMenuHandler(handler: (() -> Unit)?) { onMenu = handler }

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
        live.nx = dx.toFloat(); live.ny = dy.toFloat()
        if (dx != prevX) {
            if (prevX < 0) actionUp(el.left) else if (prevX > 0) actionUp(el.right)
            if (dx < 0) actionDown(el.left) else if (dx > 0) actionDown(el.right)
        }
        if (dy != prevY) {
            if (prevY < 0) actionUp(el.up) else if (prevY > 0) actionUp(el.down)
            if (dy < 0) actionDown(el.up) else if (dy > 0) actionDown(el.down)
        }
        var n = 0
        n = put(n, EV_ABS, el.axisX, dx * 32767)
        n = put(n, EV_ABS, el.axisY, dy * 32767)
        sendPad(n)
    }

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

    private fun digitalDir(dx: Float, dy: Float, len: Float, deadzone: Float, eight: Boolean): Pair<Int, Int> {
        if (len < deadzone) return 0 to 0
        if (!eight) {
            return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy))
                (if (dx < 0) -1 else 1) to 0
            else
                0 to (if (dy < 0) -1 else 1)
        }
        val angle = atan2(dy, dx)
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
            if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx < 0) -1 else 1) to 0
            else 0 to (if (dy < 0) -1 else 1)
        } else ix to iy
    }

    private fun buttonDown(el: ControlElement.Button) {
        if (el.id !in pressedButtons) pressedButtons += el.id
        if (el.action is ControlAction.Menu) {
            onMenu?.invoke()
            return
        }
        if (el.toggle) {
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
        if (id in fingers.values) return
        when (val el = elementById(id)) {
            is ControlElement.Button -> buttonUp(el)
            is ControlElement.Stick -> stickUp(el)
            is ControlElement.DPad -> dpadUp(el)
            null -> {}
        }
    }

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
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val id = fingers[pid] ?: continue
                    val el = elementById(id) ?: continue
                    val spot = spots[id] ?: continue
                    val x = e.getX(i); val y = e.getY(i)
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
        dpadLive.clear()
        closePad()
    }

    @Composable
    fun Overlay(profile: ControlProfile, onMenu: () -> Unit = {}) {
        val view = LocalView.current
        DisposableEffect(profile) {
            loadProfile(profile)
            setMenuHandler(onMenu)
            onDispose {
                if (OnScreenControls.profile === profile) loadProfile(null)
                setMenuHandler(null)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val density = LocalDensity.current.density
            for (el in profile.elements) {
                if (!el.visible) continue
                when (el) {
                    is ControlElement.Button -> ControlButton(el, w, h, density, view)
                    is ControlElement.Stick -> ControlStick(el, w, h, density)
                    is ControlElement.DPad -> ControlDPad(el, w, h, density)
                }
            }
        }
    }

    @Composable
    private fun ControlButton(
        el: ControlElement.Button,
        winW: Float,
        winH: Float,
        density: Float,
        view: android.view.View,
    ) {
        val sizeDp = (BASE_BUTTON_DP * el.scale).dp
        val sizePx = BASE_BUTTON_DP * el.scale * density
        val pressed = el.id in pressedButtons
        val shape = CircleShape
        val fill = when {
            pressed -> Color.White.copy(alpha = 0.40f * el.opacity)
            else -> Color.White.copy(alpha = 0.12f * el.opacity)
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
                .background(fill, shape)
                .border(1.dp, Color.White.copy(alpha = 0.30f * el.opacity), shape)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    spots.getOrPut(el.id) { Spot(elementId = el.id, kind = Kind.Button) }.area =
                        RectF(b.left, b.top, b.right, b.bottom)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (el.action is ControlAction.Menu) {
                Icon(AppIcons.Menu, str(R.string.player_menu), tint = Color.White.copy(alpha = 0.85f * el.opacity))
            } else {
                Text(
                    el.label.ifEmpty { "•" },
                    color = Color.White.copy(alpha = 0.9f * el.opacity),
                    fontSize = if (el.label.length > 2) 11.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        DisposableEffect(el.id) {
            onDispose { spots.remove(el.id) }
        }
    }

    @Composable
    private fun ControlStick(el: ControlElement.Stick, winW: Float, winH: Float, density: Float) {
        val sizeDp = (BASE_STICK_DP * el.scale).dp
        val sizePx = BASE_STICK_DP * el.scale * density
        val live = stickLive[el.id]
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
                    spots.getOrPut(el.id) { Spot(elementId = el.id, kind = Kind.Stick) }.area =
                        RectF(b.left, b.top, b.right, b.bottom)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(Color.White.copy(alpha = 0.12f * el.opacity), radius = r, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.08f * el.opacity), radius = r * 0.95f)
                val kn = r * 0.35f
                val kx = (live?.nx ?: 0f) * (r - kn)
                val ky = (live?.ny ?: 0f) * (r - kn)
                drawCircle(
                    Color.White.copy(alpha = 0.45f * el.opacity),
                    radius = kn,
                    center = Offset(size.width / 2f + kx, size.height / 2f + ky),
                )
            }
        }
        DisposableEffect(el.id) {
            onDispose { spots.remove(el.id) }
        }
    }

    @Composable
    private fun ControlDPad(el: ControlElement.DPad, winW: Float, winH: Float, density: Float) {
        val sizeDp = (BASE_DPAD_DP * el.scale).dp
        val sizePx = BASE_DPAD_DP * el.scale * density
        val live = dpadLive[el.id]
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
                    spots.getOrPut(el.id) { Spot(elementId = el.id, kind = Kind.DPad) }.area =
                        RectF(b.left, b.top, b.right, b.bottom)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val arm = r * 0.35f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val base = Color.White.copy(alpha = 0.12f * el.opacity)
                val lit = Color.White.copy(alpha = 0.40f * el.opacity)
                // Cross shape via four circles
                val dirs = listOf(
                    Offset(0f, -arm) to (live?.dy == -1),
                    Offset(0f, arm) to (live?.dy == 1),
                    Offset(-arm, 0f) to (live?.dx == -1),
                    Offset(arm, 0f) to (live?.dx == 1),
                )
                for ((off, on) in dirs) {
                    drawCircle(
                        if (on) lit else base,
                        radius = arm * 0.55f,
                        center = Offset(cx + off.x, cy + off.y),
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.18f * el.opacity), radius = arm * 0.4f, center = Offset(cx, cy))
            }
        }
        DisposableEffect(el.id) {
            onDispose { spots.remove(el.id) }
        }
    }
}
