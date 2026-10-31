package com.pooy.vpn.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pooy.vpn.MainViewModel
import com.pooy.vpn.R
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.data.Profile
import com.pooy.vpn.service.VpnController
import com.pooy.vpn.util.formatBytes
import com.pooy.vpn.util.formatDuration
import com.pooy.vpn.util.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Actions that need an Activity (permissions, pickers, clipboard). */
class Actions(
    val onToggle: () -> Unit,
    val onPaste: () -> Unit,
    val onScan: () -> Unit,
    val onPickFile: () -> Unit,
)

private sealed interface DialogState {
    data object AddManual : DialogState
    data class Edit(val id: String) : DialogState
    data class Rename(val id: String) : DialogState
    data class DeleteProfile(val id: String) : DialogState
    data object ImportUrl : DialogState
    data object Subscriptions : DialogState
    data class DeleteSub(val id: String) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PooyApp(vm: MainViewModel, actions: Actions) {
    val status by VpnController.status.collectAsStateWithLifecycle()
    val traffic by VpnController.traffic.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val data = vm.data

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(it)
        }
    }
    LaunchedEffect(status.error) {
        val e = status.error
        if (e != null) {
            VpnController.clearError()
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(e, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("POOOY", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    var addOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { addOpen = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                        }
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            MenuItem(stringResource(R.string.menu_paste)) { addOpen = false; actions.onPaste() }
                            MenuItem(stringResource(R.string.menu_scan)) { addOpen = false; actions.onScan() }
                            MenuItem(stringResource(R.string.menu_url)) { addOpen = false; dialog = DialogState.ImportUrl }
                            MenuItem(stringResource(R.string.menu_file)) { addOpen = false; actions.onPickFile() }
                            MenuItem(stringResource(R.string.menu_manual)) { addOpen = false; dialog = DialogState.AddManual }
                        }
                    }
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            MenuItem(stringResource(R.string.menu_test_all)) { moreOpen = false; vm.testAll() }
                            MenuItem(stringResource(R.string.menu_update_subs)) { moreOpen = false; vm.updateAllSubscriptions() }
                            MenuItem(stringResource(R.string.menu_subs)) { moreOpen = false; dialog = DialogState.Subscriptions }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    ConnectionPanel(status, traffic, data.active?.name, actions.onToggle)
                }
                item {
                    Text(
                        stringResource(R.string.configs_title) + " (${data.profiles.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                if (data.profiles.isEmpty() && vm.loaded) {
                    item {
                        Text(
                            stringResource(R.string.empty_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(data.profiles, key = { it.id }) { p ->
                    ProfileCard(
                        profile = p,
                        selected = p.id == data.activeId,
                        ms = vm.latency[p.id],
                        testing = vm.testing[p.id] == true,
                        onSelect = { vm.select(p.id) },
                        onTest = { vm.test(p.id) },
                        onEdit = { dialog = DialogState.Edit(p.id) },
                        onRename = { dialog = DialogState.Rename(p.id) },
                        onDuplicate = { vm.duplicate(p.id) },
                        onDelete = { dialog = DialogState.DeleteProfile(p.id) },
                    )
                }
            }
        }
    }

    val dismiss = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        DialogState.AddManual -> ConfigDialog(
            title = stringResource(R.string.title_add_manual),
            initialName = "",
            initialRaw = "",
            nameOptional = true,
            onDismiss = dismiss,
            onSave = { n, raw -> dismiss(); vm.addManual(n, raw) },
        )
        is DialogState.Edit -> data.profiles.firstOrNull { it.id == d.id }?.let { p ->
            ConfigDialog(
                title = stringResource(R.string.title_edit),
                initialName = p.name,
                initialRaw = p.raw,
                nameOptional = true,
                onDismiss = dismiss,
                onSave = { n, raw -> dismiss(); vm.edit(p.id, n, raw) },
            )
        }
        is DialogState.Rename -> data.profiles.firstOrNull { it.id == d.id }?.let { p ->
            TextDialog(
                title = stringResource(R.string.title_rename),
                label = stringResource(R.string.field_name),
                initial = p.name,
                confirm = stringResource(R.string.act_save),
                onDismiss = dismiss,
                onConfirm = { dismiss(); vm.rename(p.id, it) },
            )
        }
        is DialogState.DeleteProfile -> data.profiles.firstOrNull { it.id == d.id }?.let { p ->
            ConfirmDialog(
                title = stringResource(R.string.title_delete_profile),
                body = stringResource(R.string.body_delete_profile, p.name),
                onDismiss = dismiss,
                onConfirm = { dismiss(); vm.delete(p.id) },
            )
        }
        DialogState.ImportUrl -> ImportUrlDialog(
            onDismiss = dismiss,
            onConfirm = { url, name, asSub -> dismiss(); vm.importUrl(url, name, asSub) },
        )
        DialogState.Subscriptions -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.title_subs)) },
            text = {
                if (data.subscriptions.isEmpty()) {
                    Text(stringResource(R.string.subs_empty))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in data.subscriptions) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        s.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { vm.updateSubscription(s.id) }) { Text(stringResource(R.string.act_update)) }
                                TextButton(onClick = { dialog = DialogState.DeleteSub(s.id) }) { Text(stringResource(R.string.act_delete)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.act_close)) } },
        )
        is DialogState.DeleteSub -> data.subscriptions.firstOrNull { it.id == d.id }?.let { s ->
            ConfirmDialog(
                title = stringResource(R.string.title_delete_sub),
                body = stringResource(R.string.body_delete_sub, s.name),
                onDismiss = { dialog = DialogState.Subscriptions },
                onConfirm = { dialog = DialogState.Subscriptions; vm.deleteSubscription(s.id) },
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

// ---------------------------------------------------------------------- connection panel

@Composable
private fun ConnectionPanel(
    status: VpnController.Status,
    traffic: VpnController.Traffic,
    activeName: String?,
    onToggle: () -> Unit,
) {
    val state = status.state
    val connected = state == VpnController.State.Connected
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val owner = LocalLifecycleOwner.current

    // Tick + traffic poll: runs only while connected AND the screen is visible (no background polling).
    LaunchedEffect(connected) {
        if (connected) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Default) { VpnController.pollTraffic() }
                    delay(1000)
                }
            }
        }
    }

    val label = when (state) {
        VpnController.State.Connected -> stringResource(R.string.state_connected)
        VpnController.State.Connecting -> stringResource(R.string.state_connecting)
        VpnController.State.Disconnected -> stringResource(R.string.state_disconnected)
    }
    val server = status.serverName ?: activeName ?: stringResource(R.string.no_server)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerRing(state)
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                server,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                formatDuration(if (connected) now - status.connectedAtMs else 0L),
                style = MaterialTheme.typography.headlineSmall,
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficStat("↑", formatBytes(traffic.up), formatSpeed(traffic.upSpeed))
                TrafficStat("↓", formatBytes(traffic.down), formatSpeed(traffic.downSpeed))
            }
            Spacer(Modifier.height(18.dp))
            val idle = state == VpnController.State.Disconnected
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = if (idle) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                },
            ) {
                Text(
                    stringResource(if (idle) R.string.btn_connect else R.string.btn_disconnect),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TrafficStat(arrow: String, total: String, speed: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$arrow $total", style = MaterialTheme.typography.titleMedium)
        Text(speed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PowerRing(state: VpnController.State) {
    val accent = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline
    // The pulse animation exists only while "Connecting"; otherwise nothing animates.
    val alpha = if (state == VpnController.State.Connecting) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    val color = if (state == VpnController.State.Disconnected) idleColor else accent
    val fill = if (state == VpnController.State.Connected) accent.copy(alpha = 0.18f) else Color.Transparent

    Box(
        modifier = Modifier
            .size(128.dp)
            .clip(CircleShape)
            .background(fill)
            .border(4.dp, color.copy(alpha = alpha), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(52.dp)) {
            val sw = size.minDimension * 0.10f
            val r = size.minDimension * 0.38f
            val c = Offset(size.width / 2, size.height / 2 + sw)
            drawArc(
                color = color,
                startAngle = -50f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            drawLine(
                color = color,
                start = Offset(c.x, c.y - r - sw),
                end = Offset(c.x, c.y),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------- list

@Composable
private fun ProfileCard(
    profile: Profile,
    selected: Boolean,
    ms: Long?,
    testing: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val invalid = stringResource(R.string.invalid_config)
    val subtitle = remember(profile.raw) {
        try {
            val s = ConfigParser.parse(profile.raw)
            "${s.protocol.uppercase()} · ${s.host}:${s.port}"
        } catch (e: Exception) {
            null
        }
    } ?: invalid
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LatencyText(ms, testing)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.act_test)) { menu = false; onTest() }
                    MenuItem(stringResource(R.string.act_edit)) { menu = false; onEdit() }
                    MenuItem(stringResource(R.string.act_rename)) { menu = false; onRename() }
                    MenuItem(stringResource(R.string.act_duplicate)) { menu = false; onDuplicate() }
                    MenuItem(stringResource(R.string.act_delete)) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun LatencyText(ms: Long?, testing: Boolean) {
    when {
        testing -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ms == null -> Unit
        ms < 0 -> Text(
            stringResource(R.string.latency_timeout),
            style = MaterialTheme.typography.bodySmall,
            color = LatencyBad,
        )
        else -> Text(
            "$ms ms",
            style = MaterialTheme.typography.bodySmall,
            color = if (ms < 300) LatencyGood else if (ms < 800) LatencyWarn else LatencyBad,
        )
    }
}

// ---------------------------------------------------------------------- dialogs

@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }, enabled = v.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.act_cancel)) } },
    )
}

@Composable
private fun ConfigDialog(
    title: String,
    initialName: String,
    initialRaw: String,
    nameOptional: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, raw: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var raw by remember { mutableStateOf(initialRaw) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(if (nameOptional) R.string.field_name_optional else R.string.field_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text(stringResource(R.string.field_link)) },
                    minLines = 3,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), raw.trim()) }, enabled = raw.isNotBlank()) {
                Text(stringResource(R.string.act_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.act_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.act_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.act_cancel)) } },
    )
}

@Composable
private fun ImportUrlDialog(onDismiss: () -> Unit, onConfirm: (url: String, name: String, asSub: Boolean) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var asSub by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_import_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.field_url)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name_optional)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = asSub, onCheckedChange = { asSub = it })
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.save_as_sub), Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim(), name.trim(), asSub) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.act_import))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.act_cancel)) } },
    )
}
