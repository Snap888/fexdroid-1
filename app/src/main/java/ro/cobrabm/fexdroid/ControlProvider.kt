package ro.cobrabm.fexdroid

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Control from a PC over adb, for repeatable tests instead of blind taps (tools/fxctl, NOTES N-055):
 *
 *   adb shell content call --uri content://ro.cobrabm.fexdroid.control --method status
 *
 * Only adb's shell user and root may call it: the manifest asks for DUMP, which no app can
 * get, and [call] checks the caller's uid as well. Every answer is one JSON object in the
 * bundle's "json" entry. Sessions are started with `am start ... --es action steam`, as before;
 * nothing here types, taps or reads credentials.
 */
class ControlProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("fexdroid control: adb shell only (uid $uid)")
        val ctx = context?.applicationContext ?: throw IllegalStateException("no context")
        val answer = runCatching { answer(ctx, method, arg) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
        return Bundle().apply { putString("json", answer.toString()) }
    }

    private fun answer(ctx: android.content.Context, method: String, arg: String?): JSONObject {
        val env = LinuxEnv(ctx)
        val out = JSONObject().put("ok", true)
        when (method) {
            "status" -> {
                val s = GameSession.state
                out.put("state", s::class.simpleName)
                when (s) {
                    is SessionState.Starting -> out.put("game", s.game.tag).put("step", s.step.name).put("detail", s.detail)
                    is SessionState.Running -> out.put("game", s.game.tag).put("runningMs", System.currentTimeMillis() - s.sinceMs)
                    is SessionState.Exited -> out.put("game", s.game.tag).put("exitCode", s.code)
                    is SessionState.Failed -> out.put("game", s.game.tag).put("message", s.message)
                    SessionState.Idle -> {}
                }
                out.put("playerVisible", GameSession.playerVisible).put("inputReady", GameSession.inputReady)
                    .put("resolution", GameSession.resolution.label)
                val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                out.put("version", pkg.versionName).put("commit", BuildConfig.GIT_SHA)
                val i = InstallStatus.check(env)
                out.put("installed", JSONObject().put("payload", i.payloadReady).put("steamRootfs", i.steamRootfs)
                    .put("steamClient", i.steamClient).put("dota", i.dota).put("gameRootfs", i.gameRootfs))
                out.put("counters", counters())
            }
            // Frame counters of the display bridge; fps = difference of (changed + direct) / time.
            "counters" -> out.put("counters", counters())
            "focus" -> {
                if (GameSession.state !is SessionState.Running) out.put("focus", JSONArray())
                else out.put("focus", JSONArray(PerfSnapshot.focusInfo(env)))
            }
            "procs" -> out.put("procs", JSONArray().apply {
                for (p in PerfSnapshot.processes(env.files.path))
                    put(JSONObject().put("pid", p.pid).put("name", p.name).put("rssKb", p.rssKb))
            })
            "perf" -> out.put("text", PerfSnapshot.take(env, GameSession.resolution.label))
            "log" -> out.put("lines", JSONArray(GameSession.logTail((arg?.toIntOrNull() ?: 100).coerceIn(1, 2000))))
            "report" -> out.put("text", GameSession.reportOnce(ctx) ?: "a report is being made already")
            "stop" -> Handler(Looper.getMainLooper()).post { GameSession.stop() }
            "steam-url" -> {
                val url = arg.orEmpty()
                require(STEAM_URL.matches(url)) { "not a steam:// link this accepts: $url" }
                GameSession.openSteamUrl(url)?.let { out.put("ok", false).put("error", it) }
            }
            else -> out.put("ok", false).put("error", "unknown method $method; known: $METHODS")
        }
        return out
    }

    private fun counters() = JSONObject()
        .put("elapsedMs", SystemClock.elapsedRealtime())
        .put("frames", DisplayBridge.frames())
        .put("changed", DisplayBridge.changedFrames())
        .put("direct", DisplayBridge.directFrames())

    // Not a data provider.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val METHODS = "status, counters, focus, procs, perf, log [lines], report, stop, steam-url <link>"
        /** Links to start or show something: steam://rungameid/570, steam://nav/games, ... */
        val STEAM_URL = Regex("steam://[a-z]+(/[A-Za-z0-9_.-]+)*/?")
    }
}
