package ro.cobrabm.fexdroid

import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
 * Also the target of the `--es action x|vkcube|fex-vkcube|audio-test|fex-audio-test|xev|steam|dota|custom-x86` adb intents.
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

    // ===== 1. Переменные состояния для выбора файла =====
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var customExeArgs by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            val name = ctx.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: "unknown"
            selectedFileName = name
            append("✅ Выбран файл: $name")
        }
    }

    // ===== 2. startX объявлен ЗДЕСЬ, чтобы избежать ошибок разрешения имен =====
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

    // ===== 3. Логика запуска кастомного x86-64 приложения =====
    fun launchCustomX86App() {
        val uri = selectedFileUri
        if (uri == null) {
            append("⚠️ Ошибка: не выбран файл для запуска.")
            return
        }

        thread(name = "custom-x86-app") {
            // startX уже объявлен выше, компилятор его видит
            startX().join()
            if (!session.startAudio()) {
                append("⚠️ Не удалось запустить аудио.")
            }

            val destDir = File("${env.x86Root}/home/user/apps")
            if (!destDir.exists()) destDir.mkdirs()

            val fileName = selectedFileName ?: "app.bin"
            val destFile = File(destDir, fileName)

            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setExecutable(true, false)
                destFile.setReadable(true, false)
                destFile.setWritable(true, false)
                append("📁 Файл скопирован в: ${destFile.absolutePath}")
            } catch (e: Exception) {
                append("❌ Ошибка копирования файла: ${e.message}")
                return@thread
            }

            val exePath = destFile.absolutePath
            val args = customExeArgs.split(" ").filter { it.isNotBlank() }
            val cmd = mutableListOf("${env.root}/usr/bin/FEX", exePath).apply { addAll(args) }

            append("🚀 Запуск: ${cmd.joinToString(" ")}")
            session.launch("custom-x86-${selectedFileName}", cmd)
        }
    }

    // ===== 4. Эффекты жизненного цикла =====
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
            "custom-x86" -> launchCustomX86App() // <-- Новый обработчик
        }
    }

    // ===== 5. UI =====
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
        
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startX() }) { Text("Start X") }
            Button(onClick = { thread { startSteam() } }) { Text("Steam") }
            Button(onClick = { thread { startDota() } }) { Text("Dota 2 (direct)") }
            Button(onClick = { thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) } }) { Text("vkcube arm64") }
            Button(onClick = { thread { startX().join(); session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube")) } }) { Text("vkcube x86 (FEX)") }
            OutlinedButton(onClick = { view?.requestPointerCapture() }) { Text("Capture mouse") }
            OutlinedButton(onClick = { view?.let { v -> v.requestFocus(); ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(v, 0) } }) { Text("Keyboard") }
            Button(onClick = { sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw") }) { Text("Sound test") }
            Button(onClick = { sh("tone-x86", "${env.root}/opt/fexdroid-tests/tone 3 | ${env.root}/usr/bin/FEX ${env.x86Root}/usr/bin/pacat $toneRaw") }) { Text("Sound test x86") }
            Button(onClick = { thread { startX().join(); session.launch("xev", listOf("${env.root}/usr/bin/xev")) } }) { Text("xev") }

            // ===== НОВЫЕ КНОПКИ =====
            Button(onClick = { filePickerLauncher.launch("*/*") }) {
                Text("📂 Выбрать файл x86-64")
            }
            Button(
                onClick = { launchCustomX86App() },
                enabled = selectedFileUri != null
            ) {
                Text(if (selectedFileName != null) "🚀 Запустить: $selectedFileName" else "🚀 Запустить файл (FEX)")
            }
            // =========================

            OutlinedButton(onClick = { DisplayBridge.stop(); thread(name = "x-stop") { session.stopAll() } }) { Text("Stop everything") }
        }

        if (selectedFileName != null) {
            Text(
                "Выбранный файл: $selectedFileName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        OutlinedTextField(
            value = customExeArgs,
            onValueChange = { customExeArgs = it },
            label = { Text("Аргументы запуска (опционально, через пробел)") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true
        )

        Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}