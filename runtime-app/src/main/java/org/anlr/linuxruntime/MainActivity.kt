package org.anlr.linuxruntime

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

private data class ImportedApp(
    val id: String, val name: String, val uri: String, val type: String,
    val localPath: String = "", val sha256: String = "", val sizeBytes: Long = 0L,
    val architecture: String = "Unknown", val executableCandidate: Boolean = false,
    val interpreter: String = "", val neededLibraries: List<String> = emptyList()
)

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
    val scope = rememberCoroutineScope()

    fun persist(items: List<ImportedApp>) {
        val array = JSONArray()
        items.forEach { app ->
            array.put(JSONObject().put("id", app.id).put("name", app.name).put("uri", app.uri).put("type", app.type).put("localPath", app.localPath).put("sha256", app.sha256).put("sizeBytes", app.sizeBytes).put("architecture", app.architecture).put("executableCandidate", app.executableCandidate).put("interpreter", app.interpreter)
                .put("neededLibraries", JSONArray(app.neededLibraries)))
        }
        prefs.edit().putString("apps", array.toString()).apply()
        apps = items
    }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                RuntimeStorageManager(context.filesDir).apply {
                    initialize()
                    recoverStagingReleases()
                    AppFileImporter.cleanupPartialImports(context.filesDir)
                }
            }
        }.onFailure { notice = "Ошибка инициализации хранилища runtime: ${it.message}" }
        val array = runCatching { JSONArray(prefs.getString("apps", "[]")) }.getOrDefault(JSONArray())
        apps = (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val needed = o.optJSONArray("neededLibraries")
                val libraries = needed?.let { values ->
                    (0 until values.length()).mapNotNull { n ->
                        values.optString(n).takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
                ImportedApp(
                    o.getString("id"), o.getString("name"), o.optString("uri", ""),
                    o.optString("type", "Linux file"), o.optString("localPath", ""),
                    o.optString("sha256", ""), o.optLong("sizeBytes", 0L),
                    o.optString("architecture", "Unknown"), o.optBoolean("executableCandidate", false),
                    o.optString("interpreter", ""), libraries
                )
            }.getOrNull()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Linux application"
            scope.launch {
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        AppFileImporter.import(context.contentResolver, uri, name, context.filesDir)
                    }
                }
                imported.onSuccess { file ->
                    val entry = ImportedApp(
                        id = UUID.randomUUID().toString(), name = name, uri = uri.toString(),
                        type = file.format, localPath = file.file.absolutePath, sha256 = file.sha256,
                        sizeBytes = file.sizeBytes, architecture = file.architecture,
                        executableCandidate = file.executableCandidate,
                        interpreter = file.interpreter.orEmpty(), neededLibraries = file.neededLibraries
                    )
                    persist(apps + entry)
                    notice = "Файл скопирован в приватное хранилище ANLR. Формат: ${entry.type}; архитектура: ${entry.architecture}; SHA-256: ${entry.sha256.take(16)}…"
                    selectedTab = 1
                }.onFailure { error ->
                    notice = "Импорт не выполнен: ${error.message ?: "неизвестная ошибка"}"
                }
            }
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
                1 -> AppsScreen(apps, onImport = { picker.launch(arrayOf("*/*")) }, onLaunch = { notice = "Runtime ещё не установлен. Импорт приложения сохранён; запуск будет доступен после установки FEX/Box64 и rootfs." }, onRemove = { id ->
                    apps.firstOrNull { it.id == id }?.localPath?.let { AppFileImporter.deleteManagedCopy(context.filesDir, it) }
                    persist(apps.filterNot { it.id == id })
                })
                2 -> TerminalScreen(context)
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
    var search by remember { mutableStateOf("") }
    val visibleApps = remember(apps, search) {
        val needle = search.trim().lowercase()
        apps.filter { app ->
            needle.isEmpty() || app.name.lowercase().contains(needle) ||
                app.type.lowercase().contains(needle) || app.architecture.lowercase().contains(needle)
        }.sortedBy { it.name.lowercase() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Приложения", style = MaterialTheme.typography.headlineSmall)
                Text("${apps.size} импортировано · ${visibleApps.size} показано", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onImport) { Text("＋ Импорт") }
        }
        if (apps.isNotEmpty()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Поиск по имени, формату или архитектуре") }
            )
        }
        if (apps.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пока нет приложений", style = MaterialTheme.typography.titleMedium)
                    Text("Добавьте Linux ELF, AppImage, .desktop или shell-файл через системный выбор файлов.")
                    OutlinedButton(onClick = onImport) { Text("Выбрать файл") }
                }
            }
        } else if (visibleApps.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("Ничего не найдено. Измените поисковый запрос.", Modifier.padding(18.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(visibleApps, key = { it.id }) { app ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(app.name, style = MaterialTheme.typography.titleMedium)
                            Text(app.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${app.architecture} · ${app.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                            val integrity by produceState<String>("Проверка файла…", app.localPath, app.sha256) {
                                value = withContext(Dispatchers.IO) {
                                    when {
                                        app.localPath.isBlank() || app.sha256.isBlank() -> "Старая запись · контрольная сумма отсутствует"
                                        !File(app.localPath).isFile -> "Файл отсутствует"
                                        AppFileImporter.verify(File(app.localPath), app.sha256) -> "Целостность: SHA-256 совпадает"
                                        else -> "ОШИБКА: файл изменён или повреждён"
                                    }
                                }
                            }
                            Text("SHA-256: ${app.sha256.take(20)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(integrity, style = MaterialTheme.typography.bodySmall, color = if (integrity.startsWith("ОШИБКА")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (app.executableCandidate) "ELF/скрипт-кандидат · запуск ожидает Linux backend" else "Требуется обработчик формата · запуск отключён", style = MaterialTheme.typography.bodySmall)
                            if (app.interpreter.isNotBlank()) Text("Интерпретатор: ${app.interpreter}", style = MaterialTheme.typography.bodySmall)
                            if (app.neededLibraries.isNotEmpty()) Text(
                                "DT_NEEDED: ${app.neededLibraries.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onLaunch(app) }) { Text("Запустить") }
                                OutlinedButton(onClick = { onRemove(app.id) }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
        Text("Импорт копирует файл в приватное хранилище ANLR, вычисляет SHA-256 и определяет ELF e_machine. Это проверка формата, а не гарантия безопасности. Исполнение отключено до установки Linux rootfs и backend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun TerminalScreen(context: Context) {
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
                    val result = HostCommandRunner.run(cmd, context.filesDir)
                    buildString {
                        append(result.output.ifBlank { "(no output)" })
                        if (result.timedOut) append("\\nCommand timed out after 12 seconds")
                        if (result.truncated) append("\\n[output truncated at 12 KiB; process stopped]")
                        result.exitCode?.let { append("\\n[exit $it]") }
                        append('\n')
                    }
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
