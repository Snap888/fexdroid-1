package ro.cobrabm.fexdroid

import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

/**
 * Developer X11 test screen (Avansat › Ecran X11): Xvfb + bridge + test clients.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayScreen(autoAction: String?) {
    val ctx = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val env = remember { LinuxEnv(ctx) }
    var log by remember { mutableStateOf("") }
    
    fun append(line: String) {
        Log.i("fexdroid-display", line)
        log = (log + line + "\n").takeLast(10000)
    }
    
    val session = remember {
        val r = AppSettings.resolution
        XSession(env, r.width, r.height) { line -> append(line) }
    }
    
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var view by remember { mutableStateOf<InputSurfaceView?>(null) }
    var frames by remember { mutableStateOf(0L) }

    var logExpanded by remember { mutableStateOf(false) }
    val logScrollState = rememberScrollState()

    // =========================================================================
    // 1. ФУНКЦИИ
    // =========================================================================

    fun startX() = thread(name = "start-x") {
        if (!env.ensureInstalled(::append)) return@thread
        if (!session.startX()) { append("Xvfb did not report a framebuffer (see the log)."); return@thread }
        val h = holder ?: run { append("The surface is not ready."); return@thread }
        append(DisplayBridge.start(h.surface, session.fxshmSocket, session.shmid, AppSettings.fps, session.presentSocket))
        append(XInput.connect(0))
        view?.post { view?.inputEnabled = true; view?.requestFocus() }
    }

    fun startSteam() {
        startX().join()
        session.startAudio()
        session.launch("steam", Game.STEAM.argv(env))
    }

    fun startDota() {
        startX().join()
        session.startAudio()
        session.launch("dota", Game.DOTA.argv(env))
    }

    fun sh(title: String, cmd: String) = thread {
        startX().join()
        if (session.startAudio()) session.launch(title, listOf("${env.root}/bin/sh", "-c", cmd))
    }

    var customExeDir by remember { mutableStateOf("${env.x86Root}/usr/bin") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var customExeArgs by remember { mutableStateOf("") }
    var customEnvVars by remember { mutableStateOf("") }
    var isAppImageMode by remember { mutableStateOf(false) }
    var extractDir by remember { mutableStateOf("") }
    var extractedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var isExtracting by remember { mutableStateOf(false) }
    
    var availableBinaries by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedFolderMenu by remember { mutableStateOf(false) }
    var expandedFileMenu by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    fun scanLinuxDirectory(dirPath: String) {
        isScanning = true
        selectedFileName = null
        extractedFiles = emptyList()
        thread(name = "scan-binaries") {
            try {
                val dir = File(dirPath)
                if (!dir.exists()) {
                    append("️ Директория не существует: $dirPath")
                    availableBinaries = emptyList()
                    isScanning = false
                    return@thread
                }
                val files = dir.listFiles()?.filter { it.isFile }
                    ?.map { it.name }?.sorted() ?: emptyList()
                availableBinaries = files.take(500)
                append("📂 Найдено файлов в $dirPath: ${files.size}")
                if (files.isNotEmpty()) {
                    append("   Примеры: ${files.take(5).joinToString(", ")}")
                }
            } catch (e: Exception) {
                append("❌ Ошибка чтения директории: ${e.message}")
                availableBinaries = emptyList()
            } finally {
                isScanning = false
            }
        }
    }

    fun parseEnvVars(envString: String): List<Pair<String, String>> {
        if (envString.isBlank()) return emptyList()
        return envString.split(" ").filter { it.contains("=") }.map { pair ->
            val idx = pair.indexOf("=")
            pair.substring(0, idx) to pair.substring(idx + 1)
        }
    }

    fun extractAppImage(appImagePath: String, targetDir: String) {
        if (!File(appImagePath).exists()) {
            append("❌ AppImage не найден: $appImagePath")
            return
        }
        isExtracting = true
        logExpanded = true
        thread(name = "extract-appimage") {
            try {
                val target = File(targetDir)
                if (!target.exists()) {
                    target.mkdirs()
                    append("📁 Создана директория: $targetDir")
                }
                if (!target.canWrite()) {
                    append("❌ Директория недоступна для записи: $targetDir")
                    isExtracting = false
                    return@thread
                }
                val extractCmd = "ARCHIVE='$targetDir' '$appImagePath' --appimage-extract"
                append("📦 Распаковка: $extractCmd")
                session.launch("extract-appimage", listOf("${env.root}/bin/sh", "-c", extractCmd))
                Thread.sleep(5000)
                val squashfsRoot = File(target, "squashfs-root")
                if (squashfsRoot.exists()) {
                    val files = squashfsRoot.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                    extractedFiles = files
                    append("✅ Распаковка завершена! Файлов: ${files.size}")
                    append(" squashfs-root: ${files.take(10).joinToString(", ")}")
                    val appRun = files.find { it == "AppRun" }
                    val desktopFile = files.find { it.endsWith(".desktop") }
                    if (appRun != null) append("🎯 Найден AppRun: squashfs-root/AppRun")
                    else if (desktopFile != null) append("🎯 Найден .desktop: squashfs-root/$desktopFile")
                } else {
                    append("⚠️ squashfs-root не найден после распаковки")
                }
            } catch (e: Exception) {
                append("❌ Ошибка распаковки: ${e.message}")
            } finally {
                isExtracting = false
            }
        }
    }

    fun launchCustomX86AppByPath(dirPath: String, fileName: String?) {
        if (fileName == null || fileName.isBlank()) {
            append("️ Ошибка: не выбран файл для запуска.")
            return
        }
        val fullPath = File(dirPath, fileName).absolutePath
        thread(name = "custom-x86-app") {
            startX().join()
            if (!session.startAudio()) append("️ Не удалось запустить аудио.")
            val file = File(fullPath)
            if (!file.exists()) {
                append("❌ Файл не найден: $fullPath")
                return@thread
            }
            try {
                val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "+x", fullPath))
                chmodProcess.waitFor()
                append("✅ chmod +x $fullPath")
            } catch (e: Exception) {
                append("⚠️ chmod failed: ${e.message}")
            }
            val finalArgs = mutableListOf<String>()
            val envVars = parseEnvVars(customEnvVars).toMutableList()
            if (isAppImageMode) {
                append("🔧 AppImage режим")
                val appImageTempDir = File(dirPath, ".appimage_temp")
                if (!appImageTempDir.exists()) appImageTempDir.mkdirs()
                envVars.add("TMPDIR" to appImageTempDir.absolutePath)
                envVars.add("APPIMAGE_EXTRACT_AND_RUN" to "1")
                val userArgs = customExeArgs.split(" ").filter { it.isNotBlank() }
                if (!userArgs.contains("--appimage-extract-and-run") && !userArgs.contains("--appimage-extract")) {
                    finalArgs.add("--appimage-extract-and-run")
                }
            }
            finalArgs.addAll(customExeArgs.split(" ").filter { it.isNotBlank() })
            val cmd = mutableListOf("${env.root}/usr/bin/FEX", fullPath).apply { addAll(finalArgs) }
            val envPrefix = envVars.joinToString(" ") { "${it.first}=${it.second}" }
            val fullCmdStr = if (envPrefix.isNotBlank()) "$envPrefix ${cmd.joinToString(" ")}" else cmd.joinToString(" ")
            append("🚀 Запуск: $fullCmdStr")
            session.launch("custom-x86-$fileName", listOf("${env.root}/bin/sh", "-c", fullCmdStr))
        }
    }

    fun launchExtractedApp(extractedFileName: String) {
        if (extractDir.isBlank()) {
            append("❌ Не указана директория распаковки")
            return
        }
        val squashfsRoot = File(extractDir, "squashfs-root")
        val fullPath = File(squashfsRoot, extractedFileName).absolutePath
        thread(name = "launch-extracted") {
            startX().join()
            if (!session.startAudio()) append("⚠️ Не удалось запустить аудио.")
            val file = File(fullPath)
            if (!file.exists()) {
                append("❌ Файл не найден: $fullPath")
                return@thread
            }
            try {
                val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "+x", fullPath))
                chmodProcess.waitFor()
            } catch (e: Exception) {
                append("⚠️ chmod failed: ${e.message}")
            }
            val args = customExeArgs.split(" ").filter { it.isNotBlank() }
            val cmd = mutableListOf("${env.root}/usr/bin/FEX", fullPath).apply { addAll(args) }
            val envVars = parseEnvVars(customEnvVars)
            val envPrefix = envVars.joinToString(" ") { "${it.first}=${it.second}" }
            val fullCmdStr = if (envPrefix.isNotBlank()) "$envPrefix ${cmd.joinToString(" ")}" else cmd.joinToString(" ")
            append("🚀 Распакованный: $fullCmdStr")
            session.launch("extracted-$extractedFileName", listOf("${env.root}/bin/sh", "-c", fullCmdStr))
        }
    }

    // =========================================================================
    // 2. ЭФФЕКТЫ ЖИЗНЕННОГО ЦИКЛА
    // =========================================================================
    val toneRaw = "--raw --format=s16le --rate=48000 --channels=2"

    DisposableEffect(Unit) { 
        onDispose { 
            DisplayBridge.stop()
            thread(name = "x-stop") { session.stopAll() } 
        } 
    }
    
    var audioFrames by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { 
            frames = DisplayBridge.frames()
            audioFrames = AudioBridge.frames()
            delay(1000) 
        }
    }
    
    LaunchedEffect(log) {
        if (logExpanded) {
            delay(50)
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    var autoDone by remember { mutableStateOf(false) }
    LaunchedEffect(autoAction, holder) {
        if (holder == null || autoDone) return@LaunchedEffect
        autoDone = true
        when (autoAction) {
            "x" -> startX()
            "vkcube" -> thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) }
            "audio-test" -> sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw")
            "fex-audio-test" -> sh("tone-x86", "${env.root}/opt/fexdroid-tests/tone 3 | ${env.root}/usr/bin/FEX ${env.x86Root}/usr/bin/pacat $toneRaw")
            "steam" -> thread { startSteam() }
            "dota" -> thread { startDota() }
            "xev" -> thread { 
                startX().join()
                session.launch("xev", listOf("${env.root}/bin/sh", "-c", "LD_PRELOAD=${env.root}/usr/libexec/coreutils/libstdbuf.so _STDBUF_O=L exec \"\$@\"", "sh", "${env.root}/usr/bin/xev", "-geometry", "${session.width}x${session.height}+0+0", "-event", "keyboard", "-event", "button", "-event", "mouse")) 
            }
            "fex-vkcube" -> thread { startX().join(); session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube")) }
            "custom-x86" -> launchCustomX86AppByPath(customExeDir, selectedFileName)
        }
    }

    // =========================================================================
    // 3. UI - ОСНОВНОЙ КОНТЕНТ
    // =========================================================================
    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(session.width.toFloat() / session.height),
                factory = { c ->
                    InputSurfaceView(c, session.width, session.height).apply {
                        view = this
                        this.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { holder = h }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { DisplayBridge.stop(); holder = null }
                        })
                    }
                },
            )
            
            Text("frames: $frames · sound: ${audioFrames / 48000} s", style = MaterialTheme.typography.bodySmall)
            
            Text("🛠️ Запуск x86-64 приложения (FEX)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = customExeDir,
                    onValueChange = { customExeDir = it },
                    label = { Text("📁 Папка в Linux") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { expandedFolderMenu = true }) { Text("▼", fontSize = 16.sp) }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { scanLinuxDirectory(customExeDir) }, 
                    enabled = !isScanning && customExeDir.isNotBlank(),
                    modifier = Modifier.heightIn(min = 56.dp)
                ) {
                    Text(if (isScanning) "⏳" else "📂 Scan")
                }
            }

            DropdownMenu(expanded = expandedFolderMenu, onDismissRequest = { expandedFolderMenu = false }, modifier = Modifier.fillMaxWidth()) {
                DropdownMenuItem(text = { Text("${env.x86Root}/usr/bin") }, onClick = { customExeDir = "${env.x86Root}/usr/bin"; expandedFolderMenu = false })
                DropdownMenuItem(text = { Text("${env.x86Root}/home/user/apps") }, onClick = { customExeDir = "${env.x86Root}/home/user/apps"; expandedFolderMenu = false })
            }

            OutlinedTextField(
                value = selectedFileName ?: "",
                onValueChange = { selectedFileName = it.ifBlank { null } },
                label = { Text("📄 Файл для запуска") },
                placeholder = { Text(if (availableBinaries.isEmpty()) "Сначала нажмите 'Scan'" else "Начните вводить имя...") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                enabled = availableBinaries.isNotEmpty(),
                trailingIcon = {
                    if (availableBinaries.isNotEmpty()) {
                        TextButton(onClick = { expandedFileMenu = true }) { Text("▼", fontSize = 16.sp) }
                    }
                }
            )

            DropdownMenu(expanded = expandedFileMenu, onDismissRequest = { expandedFileMenu = false }, modifier = Modifier.fillMaxWidth()) {
                availableBinaries.forEach { fileName ->
                    DropdownMenuItem(text = { Text(fileName) }, onClick = { selectedFileName = fileName; expandedFileMenu = false })
                }
            }

            if (selectedFileName?.endsWith(".AppImage", ignoreCase = true) == true) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = extractDir,
                        onValueChange = { extractDir = it },
                        label = { Text("📂 Директория распаковки") },
                        placeholder = { Text("$customExeDir/extracted") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val appImagePath = File(customExeDir, selectedFileName!!).absolutePath
                            val target = if (extractDir.isBlank()) {
                                File(customExeDir, "${selectedFileName!!.removeSuffix(".AppImage").removeSuffix(".appimage")}.extracted").absolutePath
                            } else extractDir
                            extractDir = target
                            extractAppImage(appImagePath, target)
                        },
                        enabled = !isExtracting && selectedFileName != null,
                        modifier = Modifier.heightIn(min = 56.dp)
                    ) {
                        Text(if (isExtracting) "" else "📦 Распаковать")
                    }
                }

                if (extractedFiles.isNotEmpty()) {
                    Text("📂 Распакованные файлы (${extractedFiles.size}):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        extractedFiles.take(20).forEach { fileName ->
                            OutlinedButton(onClick = { launchExtractedApp(fileName) }, modifier = Modifier.heightIn(min = 36.dp)) {
                                Text(fileName, fontSize = 10.sp)
                            }
                        }
                    }
                    if (extractedFiles.size > 20) {
                        Text("... и ещё ${extractedFiles.size - 20} файлов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(checked = isAppImageMode, onCheckedChange = { isAppImageMode = it })
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text("AppImage режим", style = MaterialTheme.typography.bodyMedium)
                    Text("Автоматически: TMPDIR, --appimage-extract-and-run", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = customEnvVars,
                onValueChange = { customEnvVars = it },
                label = { Text("🌍 Переменные окружения (KEY=VALUE через пробел)") },
                placeholder = { Text("Например: TMPDIR=/tmp SDL_VIDEODRIVER=x11") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = customExeArgs,
                onValueChange = { customExeArgs = it },
                label = { Text("Аргументы (опционально)") },
                placeholder = { Text("Например: --fullscreen") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true
            )

            Button(
                onClick = { launchCustomX86AppByPath(customExeDir, selectedFileName) },
                enabled = selectedFileName != null && customExeDir.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("🚀 Запустить через FEX")
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = { startX() }) { Text("Start X") }
                Button(onClick = { thread { startSteam() } }) { Text("Steam") }
                Button(onClick = { thread { startDota() } }) { Text("Dota 2") }
                Button(onClick = { thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) } }) { Text("vkcube arm64") }
                Button(onClick = { thread { startX().join(); session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube")) } }) { Text("vkcube x86") }
                OutlinedButton(onClick = { view?.requestPointerCapture() }) { Text("Mouse") }
                OutlinedButton(onClick = { view?.let { v -> v.requestFocus(); ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(v, 0) } }) { Text("Keyboard") }
                Button(onClick = { sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw") }) { Text("Sound") }
                Button(onClick = { thread { startX().join(); session.launch("xev", listOf("${env.root}/usr/bin/xev")) } }) { Text("xev") }
                OutlinedButton(onClick = { DisplayBridge.stop(); thread(name = "x-stop") { session.stopAll() } }) { Text("Stop") }
            }

            // Кнопка для показа/скрытия лога (всегда видна внизу основного контента)
            OutlinedButton(
                onClick = { logExpanded = !logExpanded },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
            ) {
                val logLineCount = log.count { it == '\n' }
                val logIndicator = if (logExpanded) "▲ Свернуть лог" else "▼ Показать лог ($logLineCount строк)"
                Text(logIndicator)
            }
        }

        // ===== ПЛАВАЮЩИЙ ЛОГ (оверлей поверх всего контента) =====
        if (logExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)  // Занимает 60% высоты экрана
                    .align(Alignment.BottomCenter)  // Прижат к низу
                    .zIndex(1f)  // Поверх всего
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    // Заголовок с кнопками управления
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(" Лог вывода", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { 
                                scope.launch { logScrollState.animateScrollTo(0) }
                            }) {
                                Text("⬆️", fontSize = 16.sp)
                            }
                            TextButton(onClick = { 
                                scope.launch { logScrollState.animateScrollTo(logScrollState.maxValue) }
                            }) {
                                Text("️", fontSize = 16.sp)
                            }
                            TextButton(onClick = { 
                                clipboardManager.setText(AnnotatedString(log))
                                append("📋 Лог скопирован!")
                            }) {
                                Text("📋", fontSize = 16.sp)
                            }
                            TextButton(onClick = { logExpanded = false }) {
                                Text("✕", fontSize = 18.sp)
                            }
                        }
                    }
                    
                    // Сам лог с прокруткой
                    SelectionContainer {
                        Text(
                            text = log.ifEmpty { "Ожидание событий..." }, 
                            fontFamily = FontFamily.Monospace, 
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 8.dp)
                                .verticalScroll(logScrollState)
                        )
                    }
                }
            }
        }
    }
}