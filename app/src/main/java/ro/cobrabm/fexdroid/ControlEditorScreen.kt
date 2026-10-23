package ro.cobrabm.fexdroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Full-screen editor for a [ControlProfile]: drag elements, tune scale/opacity/actions,
 * add or remove buttons, sticks and D-pads. [onSave] receives the finished profile.
 */
@Composable
fun ControlEditorScreen(
    initial: ControlProfile,
    onSave: (ControlProfile) -> Unit,
    onCancel: () -> Unit,
) {
    val elements = remember {
        mutableStateListOf<ControlElement>().also { list ->
            for (e in initial.elements) list += copyElement(e)
        }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(initial.name.ifBlank { "Custom" }) }
    val selected = elements.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // —— top bar ——
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(str(R.string.cancel)) }
                Text(
                    str(R.string.editor_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Button(onClick = {
                    onSave(ControlProfile(name = name.trim().ifEmpty { "Custom" }, elements = elements.toList()))
                }) { Text(str(R.string.editor_save)) }
            }
        }

        // —— canvas ——
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(4.dp),
        ) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val density = LocalDensity.current.density
            // Grid
            Canvas(Modifier.fillMaxSize()) {
                val step = 40.dp.toPx()
                val grid = Color.White.copy(alpha = 0.06f)
                var x = 0f
                while (x < size.width) {
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            }
            for (el in elements) {
                if (!el.visible) continue
                EditorElement(
                    el = el,
                    selected = el.id == selectedId,
                    winW = w,
                    winH = h,
                    density = density,
                    onSelect = { selectedId = el.id },
                    onMove = { nx, ny ->
                        el.x = nx.coerceIn(0.02f, 0.98f)
                        el.y = ny.coerceIn(0.02f, 0.98f)
                        // Force recomposition of list
                        val i = elements.indexOfFirst { it.id == el.id }
                        if (i >= 0) elements[i] = el
                    },
                )
            }
            if (elements.isEmpty()) {
                Text(
                    str(R.string.editor_empty),
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // —— bottom tools ——
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        val b = ControlElement.Button(
                            x = 0.5f, y = 0.5f, label = "A",
                            action = ControlAction.fromAndroidKey(android.view.KeyEvent.KEYCODE_A)
                                ?: ControlAction.PadButton(ControlAction.BTN_A),
                        )
                        elements += b
                        selectedId = b.id
                    }) { Text(str(R.string.editor_add_button)) }
                    OutlinedButton(onClick = {
                        val s = ControlElement.Stick(x = 0.2f, y = 0.7f)
                        elements += s
                        selectedId = s.id
                    }) { Text(str(R.string.editor_add_stick)) }
                    OutlinedButton(onClick = {
                        val d = ControlElement.DPad(x = 0.2f, y = 0.4f)
                        elements += d
                        selectedId = d.id
                    }) { Text(str(R.string.editor_add_dpad)) }
                    if (selected != null) {
                        TextButton(onClick = {
                            elements.removeAll { it.id == selectedId }
                            selectedId = null
                        }) { Text(str(R.string.editor_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (selected != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ElementProperties(
                        el = selected,
                        onChange = { updated ->
                            val i = elements.indexOfFirst { it.id == updated.id }
                            if (i >= 0) elements[i] = updated
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(str(R.string.editor_profile_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        str(R.string.editor_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorElement(
    el: ControlElement,
    selected: Boolean,
    winW: Float,
    winH: Float,
    density: Float,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val baseDp = when (el) {
        is ControlElement.Button -> 56f
        is ControlElement.Stick -> 140f
        is ControlElement.DPad -> 130f
    }
    val sizeDp = (baseDp * el.scale).dp
    val sizePx = baseDp * el.scale * density
    var dragX by remember(el.id) { mutableFloatStateOf(0f) }
    var dragY by remember(el.id) { mutableFloatStateOf(0f) }

    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f)
    Box(
        Modifier
            .offset {
                IntOffset(
                    (el.x * winW - sizePx / 2f + dragX).roundToInt(),
                    (el.y * winH - sizePx / 2f + dragY).roundToInt(),
                )
            }
            .size(sizeDp)
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
            .background(Color.White.copy(alpha = el.opacity * 0.35f), CircleShape)
            .pointerInput(el.id) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDragEnd = {
                        val nx = ((el.x * winW + dragX) / winW).coerceIn(0.02f, 0.98f)
                        val ny = ((el.y * winH + dragY) / winH).coerceIn(0.02f, 0.98f)
                        dragX = 0f; dragY = 0f
                        onMove(nx, ny)
                    },
                    onDragCancel = { dragX = 0f; dragY = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    },
                )
            }
            .clickable { onSelect() },
        contentAlignment = Alignment.Center,
    ) {
        when (el) {
            is ControlElement.Button -> Text(
                el.label.ifEmpty { "•" },
                color = Color.White,
                fontSize = if (el.label.length > 2) 11.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            is ControlElement.Stick -> Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                drawCircle(Color.White.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.55f), radius = size.minDimension * 0.2f)
            }
            is ControlElement.DPad -> Text("D-pad", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ElementProperties(el: ControlElement, onChange: (ControlElement) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when (el) {
                is ControlElement.Button -> str(R.string.editor_type_button)
                is ControlElement.Stick -> str(R.string.editor_type_stick)
                is ControlElement.DPad -> str(R.string.editor_type_dpad)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        when (el) {
            is ControlElement.Button -> {
                OutlinedTextField(
                    value = el.label,
                    onValueChange = { onChange(el.copy(label = it.take(8))) },
                    label = { Text(str(R.string.editor_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(str(R.string.editor_action), style = MaterialTheme.typography.bodySmall)
                ActionChips(el.action) { onChange(el.copy(action = it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.editor_toggle), modifier = Modifier.weight(1f))
                    Switch(el.toggle, onCheckedChange = { onChange(el.copy(toggle = it)) })
                }
            }
            is ControlElement.Stick -> {
                Text(str(R.string.editor_stick_mode), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in StickMode.entries) {
                        FilterChip(
                            selected = el.mode == m,
                            onClick = { onChange(el.copy(mode = m)) },
                            label = { Text(m.name) },
                        )
                    }
                }
                Text("${str(R.string.editor_deadzone)}: ${(el.deadzone * 100).roundToInt()}%")
                Slider(
                    value = el.deadzone,
                    onValueChange = { onChange(el.copy(deadzone = it)) },
                    valueRange = 0f..0.45f,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = el.axisX == ControlAction.ABS_X,
                        onClick = { onChange(el.copy(axisX = ControlAction.ABS_X, axisY = ControlAction.ABS_Y)) },
                        label = { Text(str(R.string.editor_left_stick)) },
                    )
                    FilterChip(
                        selected = el.axisX == ControlAction.ABS_RX,
                        onClick = { onChange(el.copy(axisX = ControlAction.ABS_RX, axisY = ControlAction.ABS_RY)) },
                        label = { Text(str(R.string.editor_right_stick)) },
                    )
                }
            }
            is ControlElement.DPad -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.editor_eight_way), modifier = Modifier.weight(1f))
                    Switch(el.eightWay, onCheckedChange = { onChange(el.copy(eightWay = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.editor_use_hat), modifier = Modifier.weight(1f))
                    Switch(el.useHat, onCheckedChange = { onChange(el.copy(useHat = it)) })
                }
            }
        }
        Text("${str(R.string.editor_scale)}: ${"%.2f".format(el.scale)}")
        Slider(
            value = el.scale,
            onValueChange = { v ->
                when (el) {
                    is ControlElement.Button -> onChange(el.copy(scale = v))
                    is ControlElement.Stick -> onChange(el.copy(scale = v))
                    is ControlElement.DPad -> onChange(el.copy(scale = v))
                }
            },
            valueRange = 0.4f..2.2f,
        )
        Text("${str(R.string.editor_opacity)}: ${(el.opacity * 100).roundToInt()}%")
        Slider(
            value = el.opacity,
            onValueChange = { v ->
                when (el) {
                    is ControlElement.Button -> onChange(el.copy(opacity = v))
                    is ControlElement.Stick -> onChange(el.copy(opacity = v))
                    is ControlElement.DPad -> onChange(el.copy(opacity = v))
                }
            },
            valueRange = 0.15f..1f,
        )
    }
}

@Composable
private fun ActionChips(current: ControlAction, onPick: (ControlAction) -> Unit) {
    fun key(label: String, androidKey: Int, sticky: Boolean = false): Pair<String, ControlAction> {
        val a = ControlAction.fromAndroidKey(androidKey, sticky) ?: ControlAction.None
        return label to a
    }
    fun combo(label: String, keys: List<Int>): Pair<String, ControlAction> {
        val codes = keys.mapNotNull { ControlAction.fromAndroidKey(it)?.xKeycode }
        return label to if (codes.isEmpty()) ControlAction.None else ControlAction.KeyCombo(codes)
    }
    val presets = remember {
        listOf(
            // Keyboard
            key("Esc", android.view.KeyEvent.KEYCODE_ESCAPE),
            key("Tab", android.view.KeyEvent.KEYCODE_TAB),
            key("Space", android.view.KeyEvent.KEYCODE_SPACE),
            key("Enter", android.view.KeyEvent.KEYCODE_ENTER),
            key("Shift", android.view.KeyEvent.KEYCODE_SHIFT_LEFT, sticky = true),
            key("Ctrl", android.view.KeyEvent.KEYCODE_CTRL_LEFT, sticky = true),
            key("Alt", android.view.KeyEvent.KEYCODE_ALT_LEFT, sticky = true),
            key("W", android.view.KeyEvent.KEYCODE_W),
            key("A", android.view.KeyEvent.KEYCODE_A),
            key("S", android.view.KeyEvent.KEYCODE_S),
            key("D", android.view.KeyEvent.KEYCODE_D),
            key("Q", android.view.KeyEvent.KEYCODE_Q),
            key("E", android.view.KeyEvent.KEYCODE_E),
            key("R", android.view.KeyEvent.KEYCODE_R),
            key("F", android.view.KeyEvent.KEYCODE_F),
            // Mouse
            "LMB" to ControlAction.MouseButton(1),
            "MMB" to ControlAction.MouseButton(2),
            "RMB" to ControlAction.MouseButton(3),
            // Combos
            combo("Ctrl+C", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_C)),
            combo("Ctrl+V", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_V)),
            combo("Ctrl+Z", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_Z)),
            combo("Alt+Tab", listOf(android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_TAB)),
            // Gamepad (still useful in mixed layouts)
            "Pad A" to ControlAction.PadButton(ControlAction.BTN_A),
            "Pad B" to ControlAction.PadButton(ControlAction.BTN_B),
            "Pad X" to ControlAction.PadButton(ControlAction.BTN_X),
            "Pad Y" to ControlAction.PadButton(ControlAction.BTN_Y),
            "LB" to ControlAction.PadButton(ControlAction.BTN_TL),
            "RB" to ControlAction.PadButton(ControlAction.BTN_TR),
            "Start" to ControlAction.PadButton(ControlAction.BTN_START),
            "Select" to ControlAction.PadButton(ControlAction.BTN_SELECT),
            "Menu" to ControlAction.Menu,
            "—" to ControlAction.None,
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, action) in presets) {
            val selected = when {
                action is ControlAction.PadButton && current is ControlAction.PadButton ->
                    action.code == current.code
                action is ControlAction.Key && current is ControlAction.Key ->
                    action.xKeycode == current.xKeycode && action.sticky == current.sticky
                action is ControlAction.MouseButton && current is ControlAction.MouseButton ->
                    action.button == current.button
                action is ControlAction.KeyCombo && current is ControlAction.KeyCombo ->
                    action.xKeycodes == current.xKeycodes
                else -> action == current
            }
            FilterChip(
                selected = selected,
                onClick = { onPick(action) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Deep-enough copy so the editor does not mutate the saved profile until Save. */
private fun copyElement(e: ControlElement): ControlElement = when (e) {
    is ControlElement.Button -> e.copy()
    is ControlElement.Stick -> e.copy()
    is ControlElement.DPad -> e.copy()
}
