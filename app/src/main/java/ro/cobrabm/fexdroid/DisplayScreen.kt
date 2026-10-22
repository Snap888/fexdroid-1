package ro.cobrabm.fexdroid

import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val env = remember { LinuxEnv(ctx) }
    var log by remember { mutableStateOf("") }
    fun append(line: String) {
        Log.i("fexdroid-display", line)
        log = (log + line + "\n").takeLast(6000)
    }
    val session = remember {
        val r = AppSettings.resolution
        XSession(env, r.width, r.height) { line -> append(line) }
    }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var view by remember { mutableStateOf<InputSurfaceView?>(null) }
    var frames by remember { mutableStateOf(0L) }

    // ===== 1. Переменные для выбора файла внутри Linux-контейнера =====
    var customExePath by remember { mutableStateOf("${env.x86Root}/usr/bin/vkcube") }
    var customExeArgs by remember { mutableStateOf("") }
    
    var availableBinaries by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedDropdown by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    fun scanLinuxDirectory(dirPath: String) {
        isScanning = true
        thread(name = "scan-binaries") {
            try {
                val dir = File(dirPath)
                val files = if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter { 
                        it.isFile && it.canExecute()
                    }?.map { it.name }?.sorted() ?: emptyList()
                } else {
                    emptyList()
                }
                availableBinaries = files.take(200)
                append("📂 Найдено исполняемых файлов в $dirPath: ${files.size}")
            } catch (e: Exception) {
                append("❌ Ошибка чтения директории: ${e.message}")
                availableBinaries = emptyList()
            } finally {
                isScanning = false
            }
        }
    }

    fun launchCustomX86AppByPath(linuxPath: String) {
        if (linuxPath.isBlank()) {
            append("⚠️ Ошибка: путь к исполняемому файлу пуст.")
            return
        }

        thread(name = "custom-x86-app") {
            startX().join()
            if (!session.startAudio()) {
                append("⚠️ Не удалось запустить аудио.")
            }

            val file = File(linuxPath)
            if (!file.exists()) {
                append("❌ Файл не найден по пути: $linuxPath")
                return@thread
            }

            val args = customExeArgs.split(" ").filter { it.isNotBlank() }
            val cmd = mutableListOf("${env.root}/usr/bin/FEX", linuxPath).apply { addAll(args) }

            append("🚀 Запуск: ${cmd.joinToString(" ")}")
            session.launch("custom-x86-${file.name}", cmd)
        }
    }

    // ===== 2. Базовые функции X11 =====
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

    // ===== 3. Эффекты жизненного цикла =====
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
            "custom-x86" -> launchCustomX86AppByPath(customExePath)
        }
    }

    // ===== 4. UI =====
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
        
        Text("frames shown: $frames · sound: ${audioFrames / 48000} s played", style = MaterialTheme.typography.bodySmall)
        
        // ===== ПАНЕЛЬ ЗАПУСКА КАСТОМНЫХ x86-64 ПРИЛОЖЕНИЙ =====
        Text("🛠️ Запуск x86-64 приложения (FEX)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        
        // Стабильный TextField с иконкой выпадающего списка
        OutlinedTextField(
            value = customExePath,
            onValueChange = { customExePath = it },
            label = { Text("Путь к исполняемому файлу в Linux") },
            trailingIcon = {
                IconButton(onClick = { expandedDropdown = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Открыть список")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        
        // Стабильный DropdownMenu (без устаревшего ExposedDropdownMenuBox)
        DropdownMenu(
            expanded = expandedDropdown,
            onDismissRequest = { expandedDropdown = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (availableBinaries.isEmpty() && !isScanning) {
                DropdownMenuItem(
                    text = { Text("Список пуст. Нажмите 'Сканировать'", style = MaterialTheme.typography.bodySmall) },
                    onClick = {}
                )
            } else if (isScanning) {
                DropdownMenuItem(
                    text = { Text("Сканирование...", style = MaterialTheme.typography.bodySmall) },
                    onClick = {}
                )
            } else {
                availableBinaries.forEach { fileName ->
                    DropdownMenuItem(
                        text = { Text(fileName) },
                        onClick = {
                            customExePath = "${env.x86Root}/usr/bin/$fileName"
                            expandedDropdown = false
                        }
                    )
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { scanLinuxDirectory("${env.x86Root}/usr/bin") }, enabled = !isScanning) {
                Text(if (isScanning) "Сканирование..." else "📂 Сканировать /usr/bin")
            }
            Button(onClick = { customExePath = "${env.x86Root}/usr/bin/vkcube" }) {
                Text("vkcube (by default)")
            }
        }

        OutlinedTextField(
            value = customExeArgs,
            onValueChange = { customExeArgs = it },
            label = { Text("Аргументы запуска (опционально, через пробел)") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true
        )

        Button(
            onClick = { launchCustomX86AppByPath(customExePath) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("🚀 Запустить через FEX")
        }
        // =====================================================

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = { startX() }) { Text("Start X") }
            Button(onClick = { thread { startSteam() } }) { Text("Steam") }
            Button(onClick = { thread { startDota() } }) { Text("Dota 2") }
            Button(onClick = { thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) } }) { Text("vkcube arm64") }
            Button(onClick = { thread { startX().join(); session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube")) } }) { Text("vkcube x86") }
            OutlinedButton(onClick = { view?.requestPointerCapture() }) { Text("Capture mouse") }
            OutlinedButton(onClick = { view?.let { v -> v.requestFocus(); ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(v, 0) } }) { Text("Keyboard") }
            Button(onClick = { sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw") }) { Text("Sound test") }
            Button(onClick = { thread { startX().join(); session.launch("xev", listOf("${env.root}/usr/bin/xev")) } }) { Text("xev") }
            OutlinedButton(onClick = { DisplayBridge.stop(); thread(name = "x-stop") { session.stopAll() } }) { Text("Stop everything") }
        }

        // ===== СКРОЛЛЯЩИЙСЯ ЛОГ =====
        Text(
            text = log, 
            fontFamily = FontFamily.Monospace, 
            fontSize = 10.sp, 
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}