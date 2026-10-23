package ro.cobrabm.fexdroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Winlator-style layout editor: full-screen grid, compact top toolbar,
 * floating properties panel that does not cover the whole canvas.
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
    var propsOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val selected = elements.firstOrNull { it.id == selectedId }

    val barBg = Color(0xE61C1C22)
    val accent = Color(0xFF4FC3F7)
    val gridBg = Color(0xFF0E0E12)

    Box(Modifier.fillMaxSize().background(gridBg)) {
        // —— canvas (full screen) ——
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val density = LocalDensity.current.density

            Canvas(Modifier.fillMaxSize()) {
                val step = 28.dp.toPx()
                val grid = Color.White.copy(alpha = 0.055f)
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
                // Center crosshair
                val cx = size.width / 2f
                val cy = size.height / 2f
                val cross = Color(0xE0E53935)
                drawLine(cross, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5f)
                drawLine(cross, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)
            }

            // Tap empty area → deselect / close props
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            selectedId = null
                            propsOpen = false
                        }
                    },
            )

            for (el in elements) {
                if (!el.visible) continue
                EditorElement(
                    el = el,
                    selected = el.id == selectedId,
                    accent = accent,
                    winW = w,
                    winH = h,
                    density = density,
                    onSelect = {
                        selectedId = el.id
                        // Do not auto-open props — free placement first
                    },
                    onMove = { nx, ny ->
                        el.x = nx.coerceIn(0.02f, 0.98f)
                        el.y = ny.coerceIn(0.02f, 0.98f)
                        val i = elements.indexOfFirst { it.id == el.id }
                        if (i >= 0) elements[i] = el
                    },
                )
            }

            if (elements.isEmpty()) {
                Text(
                    str(R.string.editor_empty),
                    color = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
            }
        }

        // —— compact top toolbar (Winlator-style) ——
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = barBg,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Profile / name
                Column(
                    Modifier
                        .widthIn(max = 88.dp)
                        .clickable { propsOpen = false; selectedId = null }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        str(R.string.editor_profile_name).uppercase(),
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    Text(
                        name.ifBlank { "—" },
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                ToolbarDivider()

                // Add
                Box {
                    IconButton(onClick = { addMenuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(str(R.string.editor_add_button)) },
                            onClick = {
                                addMenuOpen = false
                                val b = ControlElement.Button(
                                    x = 0.5f, y = 0.5f, label = "A",
                                    action = ControlAction.fromAndroidKey(android.view.KeyEvent.KEYCODE_A)
                                        ?: ControlAction.PadButton(ControlAction.BTN_A),
                                )
                                elements += b
                                selectedId = b.id
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(str(R.string.editor_add_stick)) },
                            onClick = {
                                addMenuOpen = false
                                val s = ControlElement.Stick(x = 0.22f, y = 0.72f)
                                elements += s
                                selectedId = s.id
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(str(R.string.editor_add_dpad)) },
                            onClick = {
                                addMenuOpen = false
                                val d = ControlElement.DPad(x = 0.22f, y = 0.42f)
                                elements += d
                                selectedId = d.id
                            },
                        )
                    }
                }

                // Delete selected
                IconButton(
                    onClick = {
                        if (selectedId != null) {
                            elements.removeAll { it.id == selectedId }
                            selectedId = null
                            propsOpen = false
                        }
                    },
                    enabled = selectedId != null,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = str(R.string.editor_delete),
                        tint = if (selectedId != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                ToolbarDivider()

                // Properties (gear)
                IconButton(
                    onClick = {
                        if (selected != null || !propsOpen) propsOpen = !propsOpen
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        AppIcons.Settings,
                        contentDescription = str(R.string.editor_action),
                        tint = if (propsOpen) accent else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                ToolbarDivider()

                // Cancel
                TextButton(onClick = onCancel) {
                    Text(str(R.string.cancel), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                // Save
                TextButton(
                    onClick = {
                        onSave(
                            ControlProfile(
                                name = name.trim().ifEmpty { "Custom" },
                                elements = elements.toList(),
                            ),
                        )
                    },
                ) {
                    Text(str(R.string.editor_save), color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // —— floating properties panel (does not block full canvas) ——
        if (propsOpen) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 12.dp)
                    .widthIn(max = 300.dp)
                    .fillMaxHeight(0.82f),
                shape = RoundedCornerShape(14.dp),
                color = barBg,
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected != null) str(R.string.editor_action) else str(R.string.editor_profile_name),
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { propsOpen = false }) {
                            Text(str(R.string.close), fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(bottom = 8.dp))

                    if (selected != null) {
                        ElementProperties(
                            el = selected,
                            onChange = { updated ->
                                val i = elements.indexOfFirst { it.id == updated.id }
                                if (i >= 0) elements[i] = updated
                            },
                        )
                    } else {
                        // Profile name when nothing selected
                        androidx.compose.material3.OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(str(R.string.editor_profile_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            str(R.string.editor_hint),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(22.dp)
            .background(Color.White.copy(alpha = 0.15f)),
    )
}

@Composable
private fun EditorElement(
    el: ControlElement,
    selected: Boolean,
    accent: Color,
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

    val borderColor = if (selected) accent else Color.White.copy(alpha = 0.35f)
    val label = when (el) {
        is ControlElement.Button -> el.label.ifBlank {
            when (val a = el.action) {
                is ControlAction.Key -> "KEY"
                is ControlAction.KeyCombo -> a.xKeycodes.joinToString("+")
                is ControlAction.PadButton -> "BTN"
                ControlAction.Menu -> "☰"
                else -> "NONE"
            }
        }
        is ControlElement.Stick -> "○"
        is ControlElement.DPad -> "✛"
    }

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
            .background(Color.White.copy(alpha = el.opacity * 0.22f), CircleShape)
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
        if (el is ControlElement.Stick || el is ControlElement.DPad) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(Color.White.copy(alpha = 0.12f), radius = r)
                drawCircle(borderColor.copy(alpha = 0.7f), radius = r, style = Stroke(2.dp.toPx()))
                if (el is ControlElement.Stick) {
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.35f)
                }
            }
        }
        Text(
            label,
            color = if (selected) accent else Color.White.copy(alpha = 0.9f),
            fontSize = if (label.length > 3) 11.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ElementProperties(el: ControlElement, onChange: (ControlElement) -> Unit) {
    Text(
        when (el) {
            is ControlElement.Button -> str(R.string.editor_type_button)
            is ControlElement.Stick -> str(R.string.editor_type_stick)
            is ControlElement.DPad -> str(R.string.editor_type_dpad)
        },
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(6.dp))

    if (el is ControlElement.Button) {
        androidx.compose.material3.OutlinedTextField(
            value = el.label,
            onValueChange = { onChange(el.copy(label = it.take(8))) },
            label = { Text(str(R.string.editor_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(str(R.string.editor_action), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        ActionChips(current = el.action) { a -> onChange(el.copy(action = a)) }
        val sticky = (el.action as? ControlAction.Key)?.sticky == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(str(R.string.editor_toggle), modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f))
            Switch(
                checked = sticky,
                onCheckedChange = { on ->
                    val a = el.action
                    if (a is ControlAction.Key) onChange(el.copy(action = a.copy(sticky = on)))
                },
                enabled = el.action is ControlAction.Key,
            )
        }
    }

    if (el is ControlElement.Stick) {
        Text(str(R.string.editor_stick_mode), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in StickMode.entries) {
                FilterChip(
                    selected = el.mode == m,
                    onClick = { onChange(el.copy(mode = m)) },
                    label = { Text(m.name) },
                )
            }
        }
        Text("${str(R.string.editor_deadzone)}: ${"%.2f".format(el.deadzone)}", color = Color.White.copy(alpha = 0.85f))
        Slider(
            value = el.deadzone,
            onValueChange = { onChange(el.copy(deadzone = it)) },
            valueRange = 0f..0.45f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = el.axisX == ControlAction.ABS_X,
                onClick = {
                    onChange(el.copy(axisX = ControlAction.ABS_X, axisY = ControlAction.ABS_Y))
                },
                label = { Text(str(R.string.editor_left_stick)) },
            )
            FilterChip(
                selected = el.axisX == ControlAction.ABS_RX,
                onClick = {
                    onChange(el.copy(axisX = ControlAction.ABS_RX, axisY = ControlAction.ABS_RY))
                },
                label = { Text(str(R.string.editor_right_stick)) },
            )
        }
    }

    if (el is ControlElement.DPad) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(str(R.string.editor_eight_way), modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f))
            Switch(checked = el.eightWay, onCheckedChange = { onChange(el.copy(eightWay = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(str(R.string.editor_use_hat), modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f))
            Switch(checked = el.useHat, onCheckedChange = { onChange(el.copy(useHat = it)) })
        }
    }

    Text(
        "${str(R.string.editor_scale)}: ${(el.scale * 100).roundToInt()}%",
        color = Color.White.copy(alpha = 0.85f),
    )
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
    Text(
        "${str(R.string.editor_opacity)}: ${(el.opacity * 100).roundToInt()}%",
        color = Color.White.copy(alpha = 0.85f),
    )
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
            key("1", android.view.KeyEvent.KEYCODE_1),
            key("2", android.view.KeyEvent.KEYCODE_2),
            key("3", android.view.KeyEvent.KEYCODE_3),
            key("4", android.view.KeyEvent.KEYCODE_4),
            key("I", android.view.KeyEvent.KEYCODE_I),
            key("M", android.view.KeyEvent.KEYCODE_M),
            combo("Ctrl+C", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_C)),
            combo("Ctrl+V", listOf(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_V)),
            combo("Alt+Tab", listOf(android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_TAB)),
            "LMB" to ControlAction.MouseButton(1),
            "RMB" to ControlAction.MouseButton(3),
            "MMB" to ControlAction.MouseButton(2),
            "A" to ControlAction.PadButton(ControlAction.BTN_A),
            "B" to ControlAction.PadButton(ControlAction.BTN_B),
            "X" to ControlAction.PadButton(ControlAction.BTN_X),
            "Y" to ControlAction.PadButton(ControlAction.BTN_Y),
            "LB" to ControlAction.PadButton(ControlAction.BTN_TL),
            "RB" to ControlAction.PadButton(ControlAction.BTN_TR),
            "Start" to ControlAction.PadButton(ControlAction.BTN_START),
            "Select" to ControlAction.PadButton(ControlAction.BTN_SELECT),
            "Menu" to ControlAction.Menu,
            "—" to ControlAction.None,
        )
    }
    Column {
        Text(
            currentLabel(current),
            color = Color(0xFF4FC3F7),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .heightIn(max = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Wrap chips in a flow-like double row via two rows is heavy; single scroll row is fine
            for ((label, action) in presets) {
                val sel = actionMatches(current, action)
                FilterChip(
                    selected = sel,
                    onClick = { onPick(action) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
    }
}

private fun currentLabel(a: ControlAction): String = when (a) {
    is ControlAction.Key -> "KEY ${a.xKeycode}"
    is ControlAction.KeyCombo -> a.xKeycodes.joinToString("+")
    is ControlAction.PadButton -> "BTN ${a.code}"
    is ControlAction.MouseButton -> "MOUSE ${a.button}"
    is ControlAction.PadAxis -> "AXIS ${a.code}"
    ControlAction.Menu -> "Menu"
    ControlAction.ToggleTapButton -> "Tap toggle"
    ControlAction.ToggleSwipeMode -> "Swipe toggle"
    ControlAction.None -> "NONE"
}

private fun actionMatches(a: ControlAction, b: ControlAction): Boolean = when {
    a is ControlAction.Key && b is ControlAction.Key -> a.xKeycode == b.xKeycode
    a is ControlAction.KeyCombo && b is ControlAction.KeyCombo -> a.xKeycodes == b.xKeycodes
    a is ControlAction.PadButton && b is ControlAction.PadButton -> a.code == b.code
    a is ControlAction.MouseButton && b is ControlAction.MouseButton -> a.button == b.button
    a is ControlAction.Menu && b is ControlAction.Menu -> true
    a is ControlAction.None && b is ControlAction.None -> true
    else -> a == b
}

private fun copyElement(e: ControlElement): ControlElement = when (e) {
    is ControlElement.Button -> e.copy()
    is ControlElement.Stick -> e.copy()
    is ControlElement.DPad -> e.copy()
}
