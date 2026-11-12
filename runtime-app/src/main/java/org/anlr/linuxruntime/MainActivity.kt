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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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

private data class AppLaunchRequest(val app: ImportedApp, val backend: ExecutionBackend)

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
    var launchRequest by remember { mutableStateOf<AppLaunchRequest?>(null) }
    var backendChoiceApp by remember { mutableStateOf<ImportedApp?>(null) }
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
                listOf("Главная", "Приложения", "Терминал", "XFCE4", "Система").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("⌂", "▦", ">_", "▣", "↓")[index], fontSize = 18.sp) },
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
                0 -> HomeScreen(onApps = { selectedTab = 1 }, onTerminal = { selectedTab = 2 }, onDesktop = { selectedTab = 3 }, onRuntime = { selectedTab = 4 })
                1 -> AppsScreen(apps, onImport = { picker.launch(arrayOf("*/*")) }, onLaunch = { app ->
                    val elf = app.localPath.takeIf { it.isNotBlank() }?.let { runCatching { ElfInspector.inspect(File(it)) }.getOrNull() }
                    when (elf?.machine) {
                        183 -> { launchRequest = AppLaunchRequest(app, ExecutionBackend.NATIVE_ARM64); selectedTab = 2 }
                        62 -> backendChoiceApp = app
                        else -> notice = "Неподдерживаемый формат или архитектура приложения. Поддерживаются ELF AArch64 и x86-64."
                    }
                }, onRemove = { id ->
                    apps.firstOrNull { it.id == id }?.localPath?.let { AppFileImporter.deleteManagedCopy(context.filesDir, it) }
                    persist(apps.filterNot { it.id == id })
                })
                2 -> TerminalScreen(context, launchRequest, onLaunchConsumed = { launchRequest = null })
                3 -> DesktopScreen()
                else -> RuntimeManagerScreen(context) { notice = it }
            }
        }
    }
    backendChoiceApp?.let { app ->
        AlertDialog(
            onDismissRequest = { backendChoiceApp = null },
            title = { Text("Выбор x86-64 backend") },
            text = { Text("Для ${app.name} можно использовать FEX или Box64. FEX выбран по умолчанию для совместимости.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        launchRequest = AppLaunchRequest(app, ExecutionBackend.FEX_X86_64)
                        backendChoiceApp = null
                        selectedTab = 2
                    }) { Text("FEX") }
                    TextButton(onClick = {
                        launchRequest = AppLaunchRequest(app, ExecutionBackend.BOX64_X86_64)
                        backendChoiceApp = null
                        selectedTab = 2
                    }) { Text("Box64") }
                }
            },
            dismissButton = { TextButton(onClick = { backendChoiceApp = null }) { Text("Отмена") } }
        )
    }
    notice?.let { message ->
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, title = { Text("ANLR") }, text = { Text(message) })
    }
}

@Composable
private fun HomeScreen(onApps: () -> Unit, onTerminal: () -> Unit, onDesktop: () -> Unit, onRuntime: () -> Unit) {
    val info = remember { deviceSummary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux Runtime", style = MaterialTheme.typography.titleLarge)
                Text("Среда выполнения не установлена", style = MaterialTheme.typography.bodyMedium)
                Text("Сначала установите системный образ и движок трансляции. Интерфейс и менеджер приложений уже доступны.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRuntime) { Text("Установить Linux") }
                    OutlinedButton(onClick = onApps) { Text("Мои приложения") }
                }
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
                            Text(if (app.executableCandidate) "Исполняемый файл-кандидат · запуск через установленный backend" else "Формат пока не поддерживается для прямого запуска", style = MaterialTheme.typography.bodySmall)
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
        Text("Импорт копирует файл в приватное хранилище ANLR и проверяет SHA-256 перед запуском. Запускаются ELF AArch64 через ARM64 и ELF x86-64 через FEX или Box64. Импортированные программы остаются недоверенным кодом.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun RuntimeManagerScreen(context: Context, onNotice: (String) -> Unit) {
    var activeId by remember { mutableStateOf<String?>(null) }
    var activeDistro by remember { mutableStateOf("Не установлена") }
    var installing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("Debian") }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Проверка установленной системы…") }
    val scope = rememberCoroutineScope()
    val localArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !installing) {
            installing = true
            progress = 0f
            progressLabel = "Копирование архива из хранилища…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val storage = RuntimeStorageManager(context.filesDir)
                        val importFile = File(storage.downloadsDirectory(), "selected-rootfs-${UUID.randomUUID()}.tar.gz")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            importFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Не удалось открыть выбранный архив")
                        try {
                            RootfsInstaller.installLocalArchive(
                                archive = importFile,
                                distribution = selected,
                                filesDir = context.filesDir,
                                maxArchiveSizeBytes = 8L * 1024L * 1024L * 1024L,
                                maxExtractedBytes = 24L * 1024L * 1024L * 1024L
                            )
                        } finally {
                            importFile.delete()
                        }
                    }
                }
                result.onSuccess { installed ->
                    activeId = installed.releaseId
                    activeDistro = selected
                    status = "Активна: $selected · ${installed.releaseId} (локальный архив)"
                    progress = 1f
                    progressLabel = "Локальная установка завершена"
                    onNotice("$selected установлена из хранилища. SHA-256 вычислен локально; автор архива криптографически не подтверждён.")
                }.onFailure { error ->
                    status = "Локальная установка не выполнена: ${error.message}"
                    progressLabel = ""
                    onNotice("Не удалось установить архив: ${error.message}")
                }
                installing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val storage = RuntimeStorageManager(context.filesDir)
                storage.initialize()
                val root = storage.activeRootDirectory()
                val id = storage.activeReleaseId()
                val osRelease = root?.let { File(it, "etc/os-release") }?.takeIf { it.isFile }?.readText().orEmpty()
                val distro = when {
                    osRelease.contains("ID=ubuntu") -> "Ubuntu"
                    osRelease.contains("ID=debian") -> "Debian"
                    root != null -> "Linux ARM64"
                    else -> "Не установлена"
                }
                Triple(id, distro, storage)
            }
        }
        result.onSuccess {
            activeId = it.first
            activeDistro = it.second
            status = if (it.first == null) "Linux-система не установлена" else "Активна: ${it.second} · ${it.first}"
        }.onFailure { status = "Ошибка проверки: ${it.message}" }
    }

    fun install(distro: String) {
        if (installing) return
        installing = true
        progress = 0f
        progressLabel = "Подготовка установки $distro…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(BuildConfig.ANLR_RELEASE_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ANLR_RELEASE_KEY_ID.isNotBlank()) {
                        "В этой APK не закреплён release public key. Нельзя безопасно установить исполняемый rootfs."
                    }
                    val slug = if (distro == "Debian") "debian" else "ubuntu"
                    val base = "https://github.com/Snap888/fexdroid-1/releases/latest/download/$slug-manifest"
                    fun fetch(url: String): String {
                        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        try {
                            connection.connectTimeout = 15000
                            connection.readTimeout = 30000
                            connection.instanceFollowRedirects = true
                            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} при загрузке $url" }
                            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } finally { connection.disconnect() }
                    }
                    val manifest = fetch("$base.json").toByteArray(Charsets.UTF_8)
                    val signature = fetch("$base.sig").trim()
                    RootfsInstaller.install(
                        manifestBytes = manifest,
                        signatureBase64 = signature,
                        trustedPublicKeyBase64 = BuildConfig.ANLR_RELEASE_PUBLIC_KEY_B64,
                        expectedKeyId = BuildConfig.ANLR_RELEASE_KEY_ID,
                        filesDir = context.filesDir,
                        maxArchiveSizeBytes = 8L * 1024L * 1024L * 1024L,
                        maxExtractedBytes = 24L * 1024L * 1024L * 1024L
                    ) { downloaded, total ->
                        progress = if (total > 0) downloaded.toFloat() / total else 0f
                        progressLabel = "Загрузка $distro: ${downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                    }
                }
            }
            result.onSuccess { installed ->
                activeId = installed.releaseId
                activeDistro = distro
                status = "Активна: $distro · ${installed.releaseId}"
                progress = 1f
                progressLabel = "Установка завершена"
                onNotice("$distro установлена и активирована. Теперь можно запускать ARM64 Linux shell.")
            }.onFailure {
                status = "Установка $distro не выполнена: ${it.message}"
                progressLabel = ""
                onNotice("Не удалось установить $distro: ${it.message}")
            }
            installing = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Linux Runtime Manager", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Текущая система", style = MaterialTheme.typography.titleMedium)
                Text(activeDistro, style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activeId != null) Text("Release ID: $activeId", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Доступные дистрибутивы", style = MaterialTheme.typography.titleMedium)
        listOf("Debian", "Ubuntu").forEach { distro ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (selected == distro) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().clickable(enabled = !installing) { selected = distro }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (distro == "Debian") "Debian 13 Trixie · ARM64" else "Ubuntu 24.04 LTS · ARM64", style = MaterialTheme.typography.titleMedium)
                        RadioButton(selected == distro, onClick = { selected = distro }, enabled = !installing)
                    }
                    Text("ARM64 rootfs · ANLR patched glibc · FEX · Box64", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { install(distro) }, enabled = !installing && BuildConfig.ANLR_RELEASE_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ANLR_RELEASE_KEY_ID.isNotBlank()) { Text(if (BuildConfig.ANLR_RELEASE_PUBLIC_KEY_B64.isBlank() || BuildConfig.ANLR_RELEASE_KEY_ID.isBlank()) "Сеть: нужен ключ" else if (activeDistro == distro) "Переустановить $distro" else "Скачать и установить") }
                }
            }
        }
        OutlinedButton(
            onClick = { localArchivePicker.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*")) },
            enabled = !installing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Установить архив из памяти устройства (.tar.gz)") }
        Text(
            "Резервная установка: выберите ранее скачанный Debian/Ubuntu ARM64 rootfs .tar.gz. Архив распаковывается в staging, проверяются shell, patched glibc ABI и соответствие дистрибутива; затем система атомарно активируется. SHA-256 вычисляется локально и не подтверждает издателя.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (installing || progressLabel.isNotBlank()) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(progressLabel, style = MaterialTheme.typography.bodySmall)
        }
        Text("Сетевая установка подписанных релизов доступна только в APK с настроенным доверенным ключом. Для тестирования без ключей используйте установку из памяти устройства.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TerminalScreen(
    context: Context,
    launchRequest: AppLaunchRequest? = null,
    onLaunchConsumed: () -> Unit = {}
) {
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("ANLR Terminal · POSIX PTY\nВыберите Android host или установленный Linux guest.\n") }
    var busy by remember { mutableStateOf(false) }
    var pty by remember { mutableStateOf<NativePty?>(null) }
    var activeRootfs by remember { mutableStateOf<File?>(null) }
    var rootfsResolved by remember { mutableStateOf(false) }
    var ptyReader by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { mutableIntStateOf(0) }
    var terminalRows by remember { mutableIntStateOf(24) }
    var terminalColumns by remember { mutableIntStateOf(80) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("anlr_terminal", Context.MODE_PRIVATE) }

    fun append(text: String) {
        output = (output + text).takeLast(48 * 1024)
    }

    fun stopPty() {
        val active = pty
        pty = null
        ptyReader?.cancel()
        ptyReader = null
        runCatching { active?.close() }
    }

    fun attachSession(session: NativePty, banner: String) {
        pty = session
        append(banner)
        ptyReader = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val decoder = Utf8StreamDecoder()
            try {
                while (isActive) {
                    val count = session.read(buffer)
                    if (count <= 0) {
                        val tail = decoder.decode(buffer, 0, 0, endOfInput = true)
                        if (tail.isNotEmpty()) withContext(Dispatchers.Main) { append(tail) }
                        break
                    }
                    val chunk = decoder.decode(buffer, 0, count)
                    if (chunk.isNotEmpty()) withContext(Dispatchers.Main) { append(chunk) }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { session.close() }
                withContext(Dispatchers.Main) {
                    if (pty === session) {
                        pty = null
                        append("\n[PTY process ended]\n")
                    }
                }
            }
        }
    }

    fun startPty(guest: Boolean = false) {
        if (pty != null || busy) return
        val session = runCatching {
            if (guest) {
                val root = activeRootfs ?: error("Debian rootfs is not installed or activated")
                LinuxGuestSession.startShell(root, rows = terminalRows, columns = terminalColumns)
            } else {
                NativePty.spawn(
                    "/system/bin/sh", listOf("-i"), context.filesDir.absolutePath,
                    terminalRows, terminalColumns
                )
            }
        }.getOrElse {
            append("\n[PTY start failed: ${it.message}]\n")
            return
        }
        attachSession(session, if (guest) "\n[Debian ARM64 guest PTY started]\n" else "\n[Android host PTY started; not a Debian guest shell]\n")
    }

    fun submit() {
        val cmd = command.trim()
        if (cmd.isEmpty() || busy) return
        command = ""
        if (cmd == "clear") { output = ""; return }
        history = (history.filterNot { it == cmd } + cmd).takeLast(100)
        historyIndex = history.size
        prefs.edit().putString("history", org.json.JSONArray(history).toString()).apply()
        if (pty != null) {
            runCatching { pty?.write((cmd + "\n").toByteArray(Charsets.UTF_8)) }
                .onFailure { append("\n[PTY write failed: ${it.message}]\n") }
            return
        }
        append("\n$ $cmd\n")
        if (cmd == "help") {
            append("Команды: help, clear, device, rootfs, uname, id, pwd, ls [path]\nЗапустите Host PTY или Linux guest PTY.\n")
            return
        }
        if (cmd == "rootfs") {
            append("Linux rootfs: не установлен или не активирован.\n")
            return
        }
        if (cmd == "device") {
            append(deviceSummary().joinToString("\n") { "${it.first}: ${it.second}" } + "\n")
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val result = HostCommandRunner.run(cmd, context.filesDir)
                    buildString {
                        append(result.output.ifBlank { "(no output)" })
                        if (result.timedOut) append("\nCommand timed out after 12 seconds")
                        if (result.truncated) append("\n[output truncated at 12 KiB; process stopped]")
                        result.exitCode?.let { append("\n[exit $it]") }
                        append('\n')
                    }
                }.getOrElse { "Error: ${it.message}\n" }
            }
            append(result)
            busy = false
        }
    }

    LaunchedEffect(launchRequest?.app?.id, launchRequest?.backend, activeRootfs) {
        val request = launchRequest
        val root = activeRootfs
        if (request != null && rootfsResolved && root != null && pty == null) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(request.app.localPath).canonicalFile
                    check(file.isFile && AppFileImporter.verify(file, request.app.sha256)) {
                        "Импортированный файл отсутствует или не прошёл проверку SHA-256"
                    }
                    val elf = ElfInspector.inspect(file) ?: error("Файл не является поддерживаемым ELF")
                    check(elf.loadable) { "ELF не является исполняемым файлом" }
                    RuntimeComponentVerifier.requireComplete(root)
                    LinuxGuestSession.startImportedApplication(
                        importedExecutable = file,
                        rootfs = root,
                        backend = request.backend,
                        rows = terminalRows,
                        columns = terminalColumns
                    )
                }
            }
            result.onSuccess { session ->
                attachSession(session, "\n[Запуск ${request.app.name} через ${request.backend}]\n")
            }.onFailure { error ->
                append("\n[Не удалось запустить ${request.app.name}: ${error.message}]\n")
            }
            onLaunchConsumed()
        } else if (request != null && rootfsResolved && root == null) {
            append("\n[Linux rootfs не установлен или не активирован]\n")
            onLaunchConsumed()
        }
    }

    LaunchedEffect(Unit) {
        activeRootfs = withContext(Dispatchers.IO) {
            runCatching { RuntimeStorageManager(context.filesDir).activeRootDirectory() }.getOrNull()
        }
        rootfsResolved = true
        val saved = runCatching { org.json.JSONArray(prefs.getString("history", "[]")) }.getOrNull()
        history = saved?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.takeLast(100) } ?: emptyList()
        historyIndex = history.size
    }
    DisposableEffect(Unit) { onDispose { stopPty() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Терминал", style = MaterialTheme.typography.headlineSmall)
                Text(if (pty != null) "POSIX PTY · active session" else "Host / Linux guest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row {
                TextButton(onClick = { if (pty == null) startPty(false) else stopPty() }) {
                    Text(if (pty == null) "Host PTY" else "Остановить")
                }
                TextButton(enabled = pty == null && activeRootfs != null, onClick = { startPty(true) }) {
                    Text("Linux guest")
                }
                TextButton(onClick = { output = "" }) { Text("Очистить") }
            }
        }
        Surface(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { size ->
                val density = context.resources.displayMetrics.density
                val cols = (size.width / (8f * density)).toInt().coerceIn(20, 240)
                val rows = (size.height / (18f * density)).toInt().coerceIn(5, 100)
                if (cols != terminalColumns || rows != terminalRows) {
                    terminalColumns = cols
                    terminalRows = rows
                    runCatching { pty?.resize(rows, cols) }
                }
            },
            color = Color(0xFF10151C), shape = RoundedCornerShape(14.dp)
        ) {
            Text(output, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), color = Color(0xFFD7E2EE), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.isCtrlPressed && event.key == Key.C && pty != null -> {
                                runCatching { pty?.write(byteArrayOf(3)) }; true
                            }
                            event.key == Key.DirectionUp && history.isNotEmpty() -> {
                                historyIndex = (historyIndex - 1).coerceAtLeast(0)
                                command = history[historyIndex]; true
                            }
                            event.key == Key.DirectionDown && history.isNotEmpty() -> {
                                historyIndex = (historyIndex + 1).coerceAtMost(history.size)
                                command = history.getOrNull(historyIndex).orEmpty(); true
                            }
                            else -> false
                        }
                    },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner -> if (command.isEmpty()) Text("Введите команду…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            )
            TextButton(enabled = !busy, onClick = { submit() }) { Text(if (busy) "…" else "Выполнить") }
        }
        Text("PTY, история, Ctrl+C и resize работают в host и guest PTY. Полноценный ANSI-терминальный эмулятор пока не подключён.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DesktopScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var componentReport by remember { mutableStateOf<RuntimeComponentReport?>(null) }
    var componentStatus by remember { mutableStateOf("Проверка установленной системы…") }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root = RuntimeStorageManager(context.filesDir).activeRootDirectory()
                    ?: error("Linux rootfs не установлен или не активирован")
                RuntimeComponentVerifier.inspect(root)
            }
        }
        result.onSuccess {
            componentReport = it
            componentStatus = if (it.desktopReady) "Компоненты XFCE4 и X11 найдены" else "Не хватает: ${it.missing.joinToString()}"
        }.onFailure { componentStatus = it.message ?: "Не удалось проверить Linux rootfs" }
    }
    val prefs = remember { context.getSharedPreferences("anlr_xfce_profile", 0) }
    var resolution by remember { mutableStateOf(prefs.getString("resolution", "1280×720") ?: "1280×720") }
    var scale by remember { mutableFloatStateOf(prefs.getFloat("scale", 1f)) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("fullscreen", true)) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Рабочий стол", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        componentReport?.desktopReady == true -> "XFCE4 установлен"
                        componentReport == null -> "Состояние XFCE4 неизвестно"
                        else -> "Компоненты XFCE4 отсутствуют"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(componentStatus)
                Text("Важно: наличие XFCE4 и Xvfb в rootfs ещё не запускает рабочий стол. В этой версии самостоятельного ANLR-приложения пока не подключён Android framebuffer/display bridge и передача ввода в X11. Поэтому полноценная графическая сессия здесь ещё не доступна.")
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
