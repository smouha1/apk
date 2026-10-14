package com.smouha.call

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smouha.call.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var pairing: PairingManager

    /** User toggled pair code visibility while already paired */
    private var forceShowPair = false

    private val requestCall = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "Phone permission granted" else "Permission denied — dialer fallback will be used",
            Toast.LENGTH_SHORT
        ).show()
        refreshUi()
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startQrScan()
        else Toast.makeText(this, getString(R.string.scan_need_camera), Toast.LENGTH_LONG).show()
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedPayload(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        pairing = PairingManager(prefs)

        binding.btnToggle.setOnClickListener { toggleBridge() }
        binding.btnPermission.setOnClickListener {
            requestCall.launch(Manifest.permission.CALL_PHONE)
        }
        binding.btnRefreshQr.setOnClickListener {
            pairing.refreshOffer()
            renderPairing()
            Toast.makeText(this, "New pair code ready", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopy.setOnClickListener {
            val text = binding.payloadText.text?.toString().orEmpty()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("smouha-pair", text))
            Toast.makeText(
                this,
                "Copied — paste in Smouha Papper → Settings → Phones",
                Toast.LENGTH_LONG
            ).show()
        }
        binding.btnTogglePair.setOnClickListener {
            forceShowPair = !forceShowPair
            renderPairing()
        }
        binding.btnScanPcQr.setOnClickListener {
            ensureCameraAndScan()
        }
        binding.btnAcceptPcPair.setOnClickListener {
            // Reverse QR: allow PC-generated secret for 2 minutes
            if (!BridgeState.running) {
                startBridgeService()
            }
            val pm = BridgeState.pairing ?: pairing
            pm.enableHostPairWindow(120_000L)
            forceShowPair = true
            renderPairing()
            Toast.makeText(this, getString(R.string.accept_pc_pair_toast), Toast.LENGTH_LONG).show()
            binding.pairHint.text = getString(R.string.accept_pc_pair_hint)
        }
        binding.btnSaveName.setOnClickListener {
            val name = binding.editDeviceName.text?.toString()?.trim().orEmpty()
            prefs.deviceName = name.ifBlank { Prefs.DEFAULT_DEVICE_NAME }
            Toast.makeText(this, "Saved: ${prefs.deviceName}", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
        }
        binding.btnBatteryOpt.setOnClickListener {
            BatteryHelper.openOptimizationSettings(this)
            binding.root.postDelayed({ renderSettings() }, 800)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showPanel(0)
                R.id.nav_activity -> {
                    showPanel(1)
                    renderActivity()
                }
                R.id.nav_settings -> {
                    showPanel(2)
                    renderSettings()
                }
            }
            true
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (prefs.autoStart && !BridgeState.running) {
            startBridgeService()
        }
        showPanel(0)
        refreshUi()
        renderPairing()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        renderPairing()
        if (binding.panelActivity.visibility == View.VISIBLE) renderActivity()
    }

    private fun showPanel(index: Int) {
        binding.panelHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelActivity.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun toggleBridge() {
        if (BridgeState.running) {
            val i = Intent(this, CallBridgeService::class.java).apply {
                action = CallBridgeService.ACTION_STOP
            }
            startService(i)
            binding.root.postDelayed({ refreshUi() }, 350)
        } else {
            startBridgeService()
            binding.root.postDelayed({ refreshUi() }, 350)
        }
    }

    private fun startBridgeService() {
        val i = Intent(this, CallBridgeService::class.java).apply {
            action = CallBridgeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun refreshUi() {
        val ip = NetworkUtils.localIpv4()
        val running = BridgeState.running

        binding.homeDeviceName.text = prefs.deviceName
        binding.homeSubtitle.text = if (running) {
            getString(R.string.status_running, ip ?: "—", Prefs.PORT)
        } else {
            getString(R.string.status_stopped)
        }

        binding.kpiBridge.text = if (running) "Online" else "Offline"
        binding.kpiBridge.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.success else R.color.danger)
        )
        binding.kpiNetwork.text = if (ip != null) "$ip:${Prefs.PORT}" else "No Wi‑Fi"

        binding.statusPill.text = if (running) "Online" else "Offline"
        binding.statusPill.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.success else R.color.danger)
        )

        binding.btnToggle.text =
            if (running) getString(R.string.stop_bridge) else getString(R.string.start_bridge)

        // Smart permission UI
        if (hasCallPermission()) {
            binding.btnPermission.visibility = View.GONE
            binding.permReadyLabel.visibility = View.VISIBLE
        } else {
            binding.btnPermission.visibility = View.VISIBLE
            binding.permReadyLabel.visibility = View.GONE
        }

        val last = ActivityLog.list(this).firstOrNull()
        if (last != null) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val kind = if (last.kind == "whatsapp") "WA" else "Call"
            val st = if (last.ok) "OK" else "Fail"
            binding.lastActionText.text =
                "$kind · ${last.phone} · $st · ${fmt.format(Date(last.ts))}\n${last.detail}"
            binding.lastActionText.setTextColor(
                ContextCompat.getColor(this, if (last.ok) R.color.success else R.color.danger)
            )
        } else {
            binding.lastActionText.text = getString(R.string.no_activity)
            binding.lastActionText.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }


    private fun ensureCameraAndScan() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) startQrScan()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startQrScan() {
        if (!BridgeState.running) startBridgeService()
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_pc_qr_hint))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    private fun handleScannedPayload(raw: String) {
        val text = raw.trim()
        try {
            val json = JSONObject(text)
            val app = json.optString("app", "")
            val secret = json.optString("secret", "").trim()
            if (secret.length < 6) {
                Toast.makeText(this, getString(R.string.scan_bad), Toast.LENGTH_LONG).show()
                return
            }
            // Accept even if app field missing (some QR only carry secret)
            val exp = json.optLong("exp", 0L)
            val pm = BridgeState.pairing ?: pairing
            pm.acceptPcOffer(secret, exp)
            forceShowPair = true
            renderPairing()
            binding.pairHint.text = getString(R.string.scan_pc_qr_hint)
            Toast.makeText(this, getString(R.string.scan_ok), Toast.LENGTH_LONG).show()
            ActivityLog.add(this, "pair", "-", true, "QR scanned — host pair window open")
        } catch (_: Exception) {
            // plain secret string?
            if (text.length in 6..64 && text.all { it.isLetterOrDigit() }) {
                val pm = BridgeState.pairing ?: pairing
                pm.acceptPcOffer(text, 0L)
                forceShowPair = true
                renderPairing()
                Toast.makeText(this, getString(R.string.scan_ok), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.scan_bad), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderPairing() {
        val ip = NetworkUtils.localIpv4()
        val pm = BridgeState.pairing ?: pairing
        val payload = pm.buildPayloadJson(ip)
        binding.payloadText.text = payload
        binding.brandAlways.text = Prefs.BRAND_TAG
        binding.brandTag.text = Prefs.BRAND_TAG

        val paired = prefs.hasPairedSession()
        if (paired && !forceShowPair) {
            binding.pairCodeBlock.visibility = View.GONE
            binding.btnTogglePair.visibility = View.VISIBLE
            binding.btnTogglePair.text = getString(R.string.show_pair)
            binding.pairHint.text = getString(R.string.pair_connected_hint)
        } else {
            binding.pairCodeBlock.visibility = View.VISIBLE
            binding.btnTogglePair.visibility = if (paired) View.VISIBLE else View.GONE
            binding.btnTogglePair.text = getString(R.string.hide_pair)
            binding.pairHint.text = getString(R.string.pair_hint)
        }
    }

    private fun renderActivity() {
        val list = binding.activityList
        list.removeAllViews()
        val entries = ActivityLog.list(this)
        binding.activityEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
        for (e in entries) {
            val row = TextView(this).apply {
                val kind = if (e.kind == "whatsapp") "WhatsApp" else "Call"
                val st = if (e.ok) "OK" else "Fail"
                text = "$kind · ${e.phone}\n$st · ${fmt.format(Date(e.ts))} · ${e.detail}"
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (e.ok) R.color.text else R.color.danger
                    )
                )
                textSize = 13f
                setPadding(16, 16, 16, 16)
                setBackgroundResource(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 10
                layoutParams = lp
            }
            list.addView(row)
        }
    }

    private fun renderSettings() {
        val unrestricted = BatteryHelper.isIgnoringOptimizations(this)
        binding.batteryStatusText.text = getString(
            if (unrestricted) R.string.battery_status_ok else R.string.battery_status_bad
        )
        binding.btnBatteryOpt.visibility = if (unrestricted) android.view.View.GONE else android.view.View.VISIBLE

        binding.editDeviceName.setText(prefs.deviceName)
        binding.switchAutoStart.isChecked = prefs.autoStart
        binding.settingsMeta.text =
            "Device ID: ${prefs.deviceId}\nPort: ${Prefs.PORT}\nSessions: ${prefs.sessionTokens().size}"
    }
}
