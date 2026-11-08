package com.example.nearbyshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.FileInputStream

class MainActivity : Activity() {

    private lateinit var client: ConnectionsClient
    private lateinit var radar: RadarView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private var peer: String? = null
    private var pending: (() -> Unit)? = null
    private val incoming = HashMap<Long, Payload>()
    private val sent = HashSet<Long>()
    private val names = HashMap<Long, String>()
    private val endpointNames = HashMap<String, String>()
    private val myName = Build.MODEL ?: "Android"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = "com.example.nearbyshare"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int = 28) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun lp(w: Int, h: Int, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun btn(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 16f; isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD; background = round(bg); stateListAnimator = null
        setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { onClick() }
    }
    private fun setSend(on: Boolean) { sendBtn.isEnabled = on; sendBtn.alpha = if (on) 1f else 0.35f }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        client = Nearby.getConnectionsClient(this)
        val bg = 0xFF0B1220.toInt(); val card = 0xFF152036.toInt()
        window.statusBarColor = bg; window.navigationBarColor = bg

        radar = RadarView(this).apply { onPeerClick = { id -> requestConn(id) } }
        val title = TextView(this).apply {
            text = "نقل مباشر"; setTextColor(Color.WHITE); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "شارك الصور والفيديو مع الأجهزة القريبة بدون إنترنت"
            setTextColor(0xFF8FA3C4.toInt()); textSize = 14f
        }
        status = TextView(this).apply {
            text = "اختر استقبال أو بحث"; setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER; background = round(card, 20)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val recv = btn("📥 استقبال", 0xFF0A84FF.toInt()) { ensure { advertise() } }
        val find = btn("🔍 بحث", 0xFF5E5CE6.toInt()) { ensure { discover() } }
        sendBtn = btn("📤 إرسال صور / فيديو / ملفات", 0xFF30D158.toInt()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 1)
        }
        setSend(false)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(recv, lp(0, -2, 1f, 0, 0, 6, 0)); addView(find, lp(0, -2, 1f, 6, 0, 0, 0))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg); setPadding(dp(20), dp(48), dp(20), dp(20))
            addView(title); addView(sub, lp(-2, -2, 0f, 0, 4, 0, 8))
            addView(radar, lp(-1, 0, 1f))
            addView(status, lp(-1, -2, 0f, 0, 8, 0, 12))
            addView(row, lp(-1, -2, 0f, 0, 0, 0, 10))
            addView(sendBtn, lp(-1, -2))
        })
    }

    private fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun ensure(then: () -> Unit) {
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { pending = then; requestPermissions(missing.toTypedArray(), 7) }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) pending?.invoke()
        else status.text = "الأذونات مطلوبة (فعّل Bluetooth و Wi-Fi أيضاً)"
        pending = null
    }

    private fun advertise() {
        radar.peers.clear(); radar.linked = false
        client.startAdvertising(myName, serviceId, connCb, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { radar.pulsing = true; status.text = "جهازك ظاهر للأجهزة القريبة…" }
            .addOnFailureListener { status.text = "فشل: ${it.message}" }
    }

    private fun discover() {
        radar.peers.clear(); radar.linked = false
        client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                radar.peers.add(RadarView.Peer(id, info.endpointName)); status.text = "اضغط على الجهاز للاتصال"
            }
            override fun onEndpointLost(id: String) { radar.peers.removeAll { it.id == id } }
        }, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { radar.pulsing = true; status.text = "جارٍ البحث عن أجهزة قريبة…" }
            .addOnFailureListener { status.text = "فشل: ${it.message}" }
    }

    private fun requestConn(id: String) {
        status.text = "جارٍ الاتصال…"
        client.requestConnection(myName, id, connCb)
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            endpointNames[id] = info.endpointName
            AlertDialog.Builder(this@MainActivity)
                .setTitle("اتصال مع ${info.endpointName}")
                .setMessage("رمز التحقق: ${info.authenticationDigits}\nتأكد أنه نفس الرمز على الجهاز الآخر")
                .setPositiveButton("قبول") { _, _ -> client.acceptConnection(id, payCb) }
                .setNegativeButton("رفض") { _, _ -> client.rejectConnection(id) }
                .setCancelable(false).show()
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                peer = id; client.stopAdvertising(); client.stopDiscovery()
                radar.pulsing = false; radar.peers.clear()
                radar.peers.add(RadarView.Peer(id, endpointNames[id] ?: "?")); radar.linked = true
                radar.buzz(); setSend(true)
                status.text = "✅ متصل بـ ${endpointNames[id]}"
            } else status.text = "تعذر الاتصال"
        }
        override fun onDisconnected(id: String) {
            peer = null; setSend(false); radar.linked = false; radar.peers.clear(); radar.progress = -1f
            status.text = "انقطع الاتصال"
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val to = peer ?: return
        if (req != 1 || res != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        for (u in uris) {
            val name = contentResolver.query(u, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "file"
            val pfd = contentResolver.openFileDescriptor(u, "r") ?: continue
            val p = Payload.fromFile(pfd)
            sent.add(p.id)
            client.sendPayload(to, Payload.fromBytes("${p.id}|$name".toByteArray()))
            client.sendPayload(to, p)
        }
        status.text = "جارٍ الإرسال…"
    }

    private val payCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            when (p.type) {
                Payload.Type.BYTES -> String(p.asBytes()!!).split("|", limit = 2).let {
                    if (it.size == 2) names[it[0].toLong()] = it[1]
                }
                Payload.Type.FILE -> incoming[p.id] = p
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            val isFile = incoming.containsKey(u.payloadId) || sent.contains(u.payloadId)
            when (u.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    if (isFile && u.totalBytes > 0) {
                        radar.progress = u.bytesTransferred.toFloat() / u.totalBytes
                        status.text = "نقل… ${u.bytesTransferred * 100 / u.totalBytes}%"
                    }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val inc = incoming.remove(u.payloadId)
                    if (inc != null) { save(inc); radar.progress = -1f; radar.celebrate() }
                    else if (sent.remove(u.payloadId)) {
                        radar.progress = -1f; radar.celebrate(); status.text = "✅ تم الإرسال"
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> { radar.progress = -1f; status.text = "❌ فشل النقل" }
                else -> {}
            }
        }
    }

    private fun save(p: Payload) {
        val name = names.remove(p.id) ?: "file_${p.id}"
        val pfd = p.asFile()?.asParcelFileDescriptor() ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/NearbyShare")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { o -> FileInputStream(pfd.fileDescriptor).use { it.copyTo(o) } }
        pfd.close()
        status.text = "✅ تم استلام $name (Download/NearbyShare)"
    }

    override fun onDestroy() { client.stopAllEndpoints(); super.onDestroy() }
}
