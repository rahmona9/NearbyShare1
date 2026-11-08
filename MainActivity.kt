package com.example.nearbyshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        client = Nearby.getConnectionsClient(this)
        radar = RadarView(this).apply { onPeerClick = { id -> requestConn(id) } }
        status = TextView(this).apply { text = "اختر استقبال أو بحث"; textSize = 17f; setPadding(40, 24, 40, 12) }
        val recv = Button(this).apply { text = "📥 استقبال"; setOnClickListener { ensure { advertise() } } }
        val find = Button(this).apply { text = "🔍 بحث"; setOnClickListener { ensure { discover() } } }
        sendBtn = Button(this).apply {
            text = "📤 إرسال صور / فيديو / ملفات"; isEnabled = false
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, 1)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(recv, LinearLayout.LayoutParams(0, -2, 1f)); addView(find, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 72, 0, 24)
            addView(radar, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status); addView(row); addView(sendBtn)
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
                radar.buzz(); sendBtn.isEnabled = true
                status.text = "✅ متصل بـ ${endpointNames[id]}"
            } else status.text = "تعذر الاتصال"
        }
        override fun onDisconnected(id: String) {
            peer = null; sendBtn.isEnabled = false; radar.linked = false; radar.peers.clear(); radar.progress = -1f
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
