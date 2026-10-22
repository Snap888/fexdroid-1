package ro.cobrabm.fexdroid

import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import kotlinx.coroutines.delay
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
    val env = remember { LinuxEnv(ctx) }
    var log by remember { mutableStateOf("") }
    
    fun append(line: String) {
        Log.i("fexdroid-display", line)
        log = (log + line + "\n").takeLast(8000) // Увеличили буфер лога до 8000 символов
    }
    
    val session = remember {
        val r = AppSettings.resolution
        XSession(env, r.width, r.height) { line -> append(line) }
    }
    
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var view by remember { mutableStateOf<InputSurfaceView?>(null) }
    var frames by remember { mutableStateOf(0L) }

    // =========================================================================
    // 1. ФУНКЦИИ (строго сверху вниз)
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

    // ===== Переменные для запуска приложений =====
    var customExeDir by remember { mutableStateOf("${env.x86Root}/usr/bin") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var customExeArgs by remember { mutableStateOf("") }
    
    var availableBinaries by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedFolderMenu by remember { mutableStateOf(false) }
    var expandedFileMenu by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    fun scanLinuxDirectory(dirPath: String) {
        isScanning = true
        selectedFileName = null // Сбрасываем выбор файла при новом сканировании
        thread(name = "scan-binaries") {
            try {
                val dir = File(dirPath)
                if (!dir.exists()) {
                    append("⚠️ Директория не существует: $dirPath")
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

    fun launchCustomX86AppByPath(dirPath: String, fileName: String?) {
        if (fileName == null || fileName.isBlank()) {
            append("⚠️ Ошибка: не выбран файл для запуска.")
            return
        }

        val fullPath = File(dirPath, fileName).absolutePath
        
        thread(name = "custom-x86-app") {
            startX().join()
            if (!session.startAudio()) {
                append("⚠️ Не удалось запустить аудио.")
            }

            val file = File(fullPath)
            if (!file.exists()) {
                append("❌ Файл не найден по пути: $fullPath")
                return@thread
            }

            // Делаем файл исполняемым (chmod +x)
            try {
                val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "+x", fullPath))
                chmodProcess.waitFor()
                append("✅ Файл сделан исполняемым: chmod +x $fullPath")
            } catch (e: Exception) {
                append("⚠️ Не удалось сделать файл исполняемым: ${e.message}")
            }

            val args = customExeArgs.split(" ").filter { it.isNotBlank() }
            val cmd = mutableListOf("${env.root}/usr/bin/FEX", fullPath).apply { addAll(args) }

            append("🚀 Запуск: ${cmd.joinToString(" ")}")
            session.launch("custom-x86-$fileName", cmd)
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
    // 3. UI
    // =========================================================================
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
        
        // ===== УЛУЧШЕННАЯ ПАНЕЛЬ ЗАПУСКА =====
        Text("🛠️ Запуск x86-64 приложения (FEX)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        
        // Строка 1: Путь к папке + Кнопка сканирования
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
                modifier = Modifier.heightIn(min = 56.dp) // Выравнивание по высоте с TextField
            ) {
                Text(if (isScanning) "⏳" else "📂 Scan")
            }
        }

        // Выпадающее меню для папок
        DropdownMenu(
            expanded = expandedFolderMenu,
            onDismissRequest = { expandedFolderMenu = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            DropdownMenuItem(text = { Text("${env.x86Root}/usr/bin") }, onClick = { customExeDir = "${env.x86Root}/usr/bin"; expandedFolderMenu = false })
            DropdownMenuItem(text = { Text("${env.x86Root}/home/user/apps") }, onClick = { customExeDir = "${env.x86Root}/home/user/apps"; expandedFolderMenu = false })
        }

        // Строка 2: Выбор файла (активно только после сканирования)
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

        // Выпадающее меню для файлов
        DropdownMenu(
            expanded = expandedFileMenu,
            onDismissRequest = { expandedFileMenu = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            availableBinaries.forEach { fileName ->
                DropdownMenuItem(
                    text = { Text(fileName) },
                    onClick = {
                        selectedFileName = fileName
                        expandedFileMenu = false
                    }
                )
            }
        }

        // Строка 3: Аргументы и Кнопка запуска
        OutlinedTextField(
            value = customExeArgs,
            onValueChange = { customExeArgs = it },
            label = { Text("Аргументы (опционально)") },
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
        // =======================================

        // Стандартные кнопки
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

        // ===== ЛОГ С ВОЗМОЖНОСТЬЮ ВЫДЕЛЕНИЯ И КОПИРОВАНИЯ =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📜 Лог вывода", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { 
                clipboardManager.setText(AnnotatedString(log))
                append("📋 Лог скопирован в буфер обмена!")
            }) {
                Text("📋 Копировать")
            }
        }
        
        SelectionContainer {
            Text(
                text = log.ifEmpty { "Ожидание событий..." }, 
                fontFamily = FontFamily.Monospace, 
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp) // Ограничиваем высоту, чтобы не занимать весь экран
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), 
                        RoundedCornerShape(8.dp)
                    )
            )
        }
    }
}