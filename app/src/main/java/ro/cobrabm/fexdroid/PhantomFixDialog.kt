package ro.cobrabm.fexdroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Shown before Steam starts while Android's child-process limit is on (PhantomFix): the
 * automatic fix through Wireless debugging, the manual ways, or start anyway. Without
 * [onStart] (opened from the compatibility checks) it only fixes.
 */
@Composable
fun PhantomFixDialog(status: PhantomFix.Status, onStart: (() -> Unit)?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val st = PhantomFix.state
    var dontAsk by remember { mutableStateOf(false) }
    var devOn by remember { mutableStateOf(PhantomFix.developerOptionsOn(ctx)) }
    var notificationsOn by remember { mutableStateOf(true) }
    // Back from Settings (or the permission prompt): Developer options or notifications may be on now.
    LifecycleResumeEffect(Unit) {
        devOn = PhantomFix.developerOptionsOn(ctx)
        notificationsOn = PhantomFix.prepareNotifications(ctx)
        onPauseOrDispose { }
    }
    val close = { if (PhantomFix.state !is PhantomFix.State.Working) PhantomFix.cancel(ctx); onDismiss() }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(str(R.string.phantom_gate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(if (status == PhantomFix.Status.UNKNOWN) R.string.phantom_gate_unknown else R.string.phantom_gate_text))
                Text(str(R.string.phantom_auto_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(str(if (devOn) R.string.phantom_auto_step1 else R.string.phantom_auto_step1_devoff), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.phantom_auto_step2), style = MaterialTheme.typography.bodySmall)
                if (!notificationsOn) {
                    Text(str(R.string.phantom_notifications_off), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                    }) { Text(str(R.string.phantom_notifications_on)) }
                }
                FixProgress(st)
                HorizontalDivider()
                Text(str(R.string.phantom_manual_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (android.os.Build.VERSION.SDK_INT >= 34) Text(str(R.string.phantom_manual_toggle), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.phantom_manual_pc), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (android.os.Build.VERSION.SDK_INT > 31) "adb shell settings put global settings_enable_monitor_phantom_procs false"
                    else "adb shell device_config set_sync_disabled_for_tests persistent; " +
                        "adb shell device_config put activity_manager max_phantom_processes 2147483647",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                if (onStart != null && st !is PhantomFix.State.Done) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontAsk, { dontAsk = it })
                    Text(str(R.string.phantom_dont_ask), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (onStart != null) Button(onClick = {
                if (dontAsk) PhantomFix.setDontAsk(ctx, true)
                PhantomFix.cancel(ctx)
                onStart()
            }, enabled = st !is PhantomFix.State.Working) {
                Text(str(if (st is PhantomFix.State.Done) R.string.phantom_start_now else R.string.phantom_start_anyway))
            }
            else TextButton(onClick = close) { Text(str(R.string.phantom_close)) }
        },
        dismissButton = { if (onStart != null) TextButton(onClick = close) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun FixProgress(st: PhantomFix.State) {
    val ctx = LocalContext.current
    when (st) {
        PhantomFix.State.Idle -> FilledTonalButton(onClick = { PhantomFix.begin(ctx); PhantomFix.openDeveloperOptions(ctx) }) {
            Text(str(R.string.phantom_start_fix))
        }
        is PhantomFix.State.Waiting, is PhantomFix.State.Failed -> {
            if (st is PhantomFix.State.Failed) Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val port = (st as? PhantomFix.State.Waiting)?.pairPort
            Text(if (port != null) str(R.string.phantom_waiting_port, port) else str(R.string.phantom_waiting),
                style = MaterialTheme.typography.bodySmall)
            ManualCode()
            TextButton(onClick = { PhantomFix.openDeveloperOptions(ctx) }) { Text(str(R.string.phantom_start_fix)) }
        }
        is PhantomFix.State.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(st.step, style = MaterialTheme.typography.bodyMedium)
        }
        PhantomFix.State.Done -> Text(str(R.string.phantom_done), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** For split screen, or when the notification does not show: the code (and port) typed in the app. */
@Composable
private fun ManualCode() {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    Text(str(R.string.phantom_manual_entry), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true,
            label = { Text(str(R.string.phantom_code_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), singleLine = true,
            label = { Text(str(R.string.phantom_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Button(onClick = { PhantomFix.submit(ctx, code, port.toIntOrNull()) }, enabled = code.length == 6,
        modifier = Modifier.fillMaxWidth()) { Text(str(R.string.phantom_pair_button)) }
}
