package com.pooy.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.pooy.vpn.service.PooyVpnService
import com.pooy.vpn.service.VpnController
import com.pooy.vpn.ui.Actions
import com.pooy.vpn.ui.PooyApp
import com.pooy.vpn.ui.PooyTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var notifAsked = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startVpnService() else vm.say(getString(R.string.msg_vpn_denied))
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, connect anyway; without it the foreground notification is just hidden.
        prepareAndStart()
    }
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) vm.importAuto(r.data?.getStringExtra(ScanActivity.EXTRA_TEXT))
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PooyTheme {
                PooyApp(
                    vm,
                    Actions(
                        onToggle = ::toggle,
                        onPaste = ::paste,
                        onScan = { scanner.launch(Intent(this, ScanActivity::class.java)) },
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    ),
                )
            }
        }
    }

    private fun toggle() {
        if (VpnController.status.value.state != VpnController.State.Disconnected) {
            startService(Intent(this, PooyVpnService::class.java).setAction(PooyVpnService.ACTION_STOP))
            return
        }
        if (vm.data.active == null) {
            vm.say(getString(R.string.msg_no_profile))
            return
        }
        val needNotif = Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needNotif) {
            notifAsked = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareAndStart()
        }
    }

    private fun prepareAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        val i = Intent(this, PooyVpnService::class.java)
            .setAction(PooyVpnService.ACTION_START)
            .putExtra(PooyVpnService.EXTRA_PROFILE_ID, vm.data.activeId)
        ContextCompat.startForegroundService(this, i)
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        vm.importAuto(text)
    }
}
