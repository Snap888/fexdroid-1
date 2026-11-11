package org.anlr.linuxruntime

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AnlrApp() }
    }
}

private data class ImportedApp(val id: String, val name: String, val uri: String, val type: String)

private fun deviceSummary(): List<Pair<String, String>> {
    val page = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toString() }.getOrDefault("unknown")
    val kgsl = File("/dev/kgsl-3d0").exists()
    val gpu = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_name")
        .firstNotNullOfOrNull { p -> runCatching { File(p).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull() }
        ?: if (kgsl) "Adreno / KGSL (model unavailable)" else "Not detected"
    val ram = runCatching { File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.substringAfter(":").trim() }.getOrDefault("unknown")
    return listOf(
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "CPU ABI" to Build.SUPPORTED_ABIS.joinToString(),
        "Page size" to "$page bytes",
        "GPU" to gpu,
        "KGSL node" to if (kgsl) "Visible" else "Not visible",
        "RAM" to ram
    )
}

@Composable
private fun AnlrApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<ImportedApp>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("anlr_apps", 0) }

    fun persist(items: List<ImportedApp>) {
        val array = JSONArray()
        items.forEach { app ->
            array.put(JSONObject().put("id", app.id).put("name", app.name).put("uri", app.uri).put("type", app.type))
        }
        prefs.edit().putString("apps", array.toString()).apply()
        apps = items
    }

    LaunchedEffect(Unit) {
        val array = runCatching { JSONArray(prefs.getString("apps", "[]")) }.getOrDefault(JSONArray())
        apps = (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                ImportedApp(o.getString("id"), o.getString("name"), o.getString("uri"), o.optString("type", "Linux file"))
            }.getOrNull()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Linux application"
            val ext = name.substringAfterLast('.', "").lowercase()
            val type = when (ext) { "appimage" -> "AppImage"; "sh" -> "Shell script"; "desktop" -> "Desktop entry"; else -> "ELF / Linux file" }
            persist(apps + ImportedApp(UUID.randomUUID().toString(), name, uri.toString(), type))
            notice = "Добавлено: $name. Для запуска потребуется установленный Linux runtime."
            selectedTab = 1
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Главная", "Приложения", "Терминал", "XFCE4").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("⌂", "▦", ">_", "▣")[index], fontSize = 18.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ANLR", style = MaterialTheme.typography.headlineMedium)
                    Text("Android Native Linux Runtime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
                    Text("DEV 0.2", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            when (selectedTab) {
                0 -> HomeScreen(onApps = { selectedTab = 1 }, onTerminal = { selectedTab = 2 }, onDesktop = { selectedTab = 3 })
                1 -> AppsScreen(apps, onImport = { picker.launch(arrayOf("*/*")) }, onLaunch = { notice = "Runtime ещё не установлен. Импорт приложения сохранён; запуск будет доступен после установки FEX/Box64 и rootfs." }, onRemove = { id -> persist(apps.filterNot { it.id == id }) })
                2 -> TerminalScreen()
                else -> DesktopScreen()
            }
        }
    }
    notice?.let { message ->
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, title = { Text("ANLR") }, text = { Text(message) })
    }
}

@Composable
private fun HomeScreen(onApps: () -> Unit, onTerminal: () -> Unit, onDesktop: () -> Unit) {
    val info = remember { deviceSummary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux Runtime", style = MaterialTheme.typography.titleLarge)
                Text("Среда выполнения не установлена", style = MaterialTheme.typography.bodyMedium)
                Text("Сначала установите системный образ и движок трансляции. Интерфейс и менеджер приложений уже доступны.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onApps) { Text("Мои приложения") }
            }
        }
        Text("Быстрый доступ", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Терминал", ">_", Modifier.weight(1f), onTerminal)
            QuickAction("Рабочий стол", "▣", Modifier.weight(1f), onDesktop)
        }
        Text("Устройство", style = MaterialTheme.typography.titleLarge)
        info.forEach { (label, value) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, modifier = Modifier.widthIn(max = 210.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("GPU/KGSL обнаружение не означает, что Vulkan/Turnip уже работает. Нужен отдельный тест Vulkan instance/device и реального рендеринга.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun QuickAction(title: String, symbol: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(symbol, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AppsScreen(apps: List<ImportedApp>, onImport: () -> Unit, onLaunch: (ImportedApp) -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Приложения", style = MaterialTheme.typography.headlineSmall)
                Text("${apps.size} импортировано", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onImport) { Text("＋ Импорт") }
        }
        if (apps.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пока нет приложений", style = MaterialTheme.typography.titleMedium)
                    Text("Добавьте Linux ELF, AppImage, .desktop или shell-файл через системный выбор файлов.")
                    OutlinedButton(onClick = onImport) { Text("Выбрать файл") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(apps, key = { it.id }) { app ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(app.name, style = MaterialTheme.typography.titleMedium)
                            Text(app.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Источник: выбранный файл · URI сохранён", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onLaunch(app) }) { Text("Запустить") }
                                OutlinedButton(onClick = { onRemove(app.id) }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
        Text("Импорт сохраняет ссылку на файл. Исполнение пока заблокировано до установки Linux rootfs и backend; APK/AppImage не запускаются средствами Android напрямую.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TerminalScreen() {
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("ANLR Terminal · Android host diagnostic shell\nLinux guest shell станет доступен после установки rootfs.\nВведите help для списка команд ANLR.\n") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val cmd = command.trim()
        if (cmd.isEmpty() || busy) return
        command = ""
        if (cmd == "clear") { output = ""; return }
        output += "\n$ $cmd\n"
        if (cmd == "help") {
            output += "Команды: help, clear, device, rootfs, uname, id, pwd, ls [path]\nДля произвольных команд используется ограниченный Android app shell (/system/bin/sh), не Linux guest.\n"
            return
        }
        if (cmd == "rootfs") {
            output += "Linux rootfs: не установлен. Команды гостевой системы пока недоступны.\n"
            return
        }
        if (cmd == "device") {
            output += deviceSummary().joinToString("\n") { "${it.first}: ${it.second}" } + "\n"
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val process = ProcessBuilder("/system/bin/sh", "-c", cmd)
                        .directory(File("/data/user/0/org.anlr.linuxruntime"))
                        .redirectErrorStream(true).start()
                    val text = process.inputStream.bufferedReader().use { it.readText() }
                    val done = process.waitFor(12, TimeUnit.SECONDS)
                    if (!done) { process.destroyForcibly(); "Command timed out after 12 seconds\n" }
                    else (text.ifBlank { "(no output)\n" } + "\n[exit ${process.exitValue()}]\n")
                }.getOrElse { "Error: ${it.message}\n" }
            }
            output += result
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Терминал", style = MaterialTheme.typography.headlineSmall)
                Text("Android host · sandbox", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            TextButton(onClick = { output = "" }) { Text("Очистить") }
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), color = Color(0xFF10151C), shape = RoundedCornerShape(14.dp)) {
            Text(output, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), color = Color(0xFFD7E2EE), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner -> if (command.isEmpty()) Text("Введите команду…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            )
            TextButton(enabled = !busy, onClick = { submit() }) { Text(if (busy) "…" else "Выполнить") }
        }
        Text("Ограничения: команды выполняются в sandbox приложения Android. Это пока не полноценный интерактивный TTY и не shell Debian.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DesktopScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("anlr_xfce_profile", 0) }
    var resolution by remember { mutableStateOf(prefs.getString("resolution", "1280×720") ?: "1280×720") }
    var scale by remember { mutableFloatStateOf(prefs.getFloat("scale", 1f)) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("fullscreen", true)) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Рабочий стол", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("XFCE4 не установлен", style = MaterialTheme.typography.titleLarge)
                Text("Здесь будут запускаться полноценная XFCE-сессия Linux, панель задач, файловый менеджер и оконный менеджер. Сейчас доступна настройка профиля, но запуск заблокирован до установки rootfs, X-сервера и аудио/ввода.")
            }
        }
        Text("Параметры сеанса", style = MaterialTheme.typography.titleMedium)
        Text("Разрешение")
        listOf("1280×720", "1600×900", "1920×1080", "По размеру экрана").forEach { value ->
            Row(Modifier.fillMaxWidth().clickable { resolution = value }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = resolution == value, onClick = { resolution = value })
                Text(value)
            }
        }
        Text("Масштаб: ${(scale * 100).toInt()}%")
        Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.75f..1.5f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Полноэкранный режим")
            Switch(checked = fullscreen, onCheckedChange = { fullscreen = it })
        }
        OutlinedButton(onClick = {
            prefs.edit().putString("resolution", resolution).putFloat("scale", scale).putBoolean("fullscreen", fullscreen).apply()
            saved = true
        }) { Text("Сохранить профиль XFCE") }
        Text(if (saved) "Профиль сохранён на устройстве." else "Профиль: $resolution · масштаб ${(scale * 100).toInt()}% · ${if (fullscreen) "fullscreen" else "windowed"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
