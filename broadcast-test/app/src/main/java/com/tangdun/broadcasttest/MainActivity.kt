package com.tangdun.broadcasttest

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private var receiver: BroadcastReceiver? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "BLE 扫描工具"
            textSize = 24f
            setTypeface(Typeface.DEFAULT_BOLD)
        })

        // 扫描按钮
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "开始扫描BLE"
            setOnClickListener { startBleScan() }
        })
        btnRow.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { stopBleScan() }
        })
        btnRow.addView(Button(this).apply {
            text = "清除"
            setOnClickListener { logText.text = "扫描结果:\n\n" }
        })
        root.addView(btnRow)

        // 广播监听状态
        root.addView(TextView(this).apply {
            text = "广播监听: com.microtechmd.cgms.aidex.action.BgEstimate"
            textSize = 12f
            setPadding(0, 8, 0, 4)
        })

        // 日志
        logText = TextView(this).apply {
            text = "点击「开始扫描BLE」查找CGM设备\n\n"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
        }
        root.addView(ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        setContentView(root)

        // BLE初始化
        val btManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        // 动态注册血糖广播接收器
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val sb = StringBuilder()
                sb.appendLine("===== $now 广播 =====")
                sb.appendLine("Action: ${intent.action}")
                intent.extras?.let { extras ->
                    for (k in extras.keySet().sorted()) {
                        sb.appendLine("  $k = ${extras.get(k)}")
                    }
                }
                Log.d("BG_TEST", sb.toString())
                runOnUiThread { logText.append(sb.toString() + "\n") }
            }
        }
        val f = IntentFilter().apply {
            addAction("com.microtechmd.cgms.aidex.action.BgEstimate")
            addAction("com.eveningoutpost.dexdrip.BgEstimate")
            addAction("com.eveningoutpost.dexdrip.BgReading")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }

        // 请求蓝牙权限
        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: run {
            log("❌ 蓝牙不可用")
            return
        }
        if (!adapter.isEnabled) {
            log("❌ 蓝牙未开启，请先开启蓝牙")
            return
        }

        // Android 12+ 权限检查
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
                log("请授予蓝牙扫描权限")
                return
            }
        }

        isScanning = true
        log("🔍 开始扫描BLE设备 (30秒)...")
        log("正在查找CGM血糖传感器...")
        log("")

        val scanner = adapter.bluetoothLeScanner ?: run {
            log("❌ BLE扫描器不可用")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val rssi = result.rssi
                val name = device.name ?: "(未知)"
                val addr = device.address

                val sb = StringBuilder()
                sb.appendLine("──────────────────────────────")
                sb.appendLine("📱 发现设备: $name")
                sb.appendLine("   地址: $addr")
                sb.appendLine("   RSSI: $rssi dBm")
                sb.appendLine("   类型: ${device.type}")

                // 扫描响应中的UUID
                val scanRecord = result.scanRecord
                if (scanRecord != null) {
                    val serviceUuids = scanRecord.serviceUuids
                    if (serviceUuids != null && serviceUuids.isNotEmpty()) {
                        sb.appendLine("   服务UUIDs:")
                        for (uuid in serviceUuids) {
                            sb.appendLine("     $uuid")
                            // 识别标准服务
                            when (uuid.toString().take(8).lowercase()) {
                                "00001808" -> sb.appendLine("       ↑ 血糖服务 (Glucose Service)")
                                "0000180a" -> sb.appendLine("       ↑ 设备信息服务")
                                "0000180f" -> sb.appendLine("       ↑ 电池服务")
                                "00001800" -> sb.appendLine("       ↑ 通用访问服务")
                                "00001801" -> sb.appendLine("       ↑ 通用属性服务")
                            }
                        }
                    }
                    val mfrData = scanRecord.getManufacturerSpecificData(0) // any manufacturer
                    if (mfrData != null) {
                        sb.appendLine("   厂商数据: ${bytesToHex(mfrData)}")
                    }
                }

                sb.appendLine("──────────────────────────────")
                Log.d("BLE_SCAN", sb.toString())
                runOnUiThread { logText.append(sb.toString() + "\n") }

                // 如果设备名包含CGM相关关键词，高亮提示
                if (name.contains("Aidex", true) || name.contains("CGM", true) ||
                    name.contains("Glucose", true) || name.contains("MD", true) ||
                    name.contains("Ottai", true) || name.contains("Micro", true) ||
                    addr.take(8).lowercase() in listOf("00:1a:7d", "a0:3b:e3", "f0:45:da")) {
                    runOnUiThread {
                        logText.append("⚠️ 此设备可能是CGM传感器！尝试连接...\n\n")
                        // 可以在这里调用 connectGatt 尝试连接
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持BLE"
                    else -> "错误码: $errorCode"
                }
                Log.e("BLE_SCAN", "扫描失败: $msg")
                runOnUiThread { logText.append("❌ 扫描失败: $msg\n") }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)

        // 30秒后自动停止
        android.os.Handler(mainLooper).postDelayed({
            if (isScanning) {
                scanner.stopScan(callback)
                isScanning = false
                runOnUiThread { logText.append("\n⏰ 扫描结束 (30秒)\n") }
            }
        }, 30000)
    }

    private fun stopBleScan() {
        isScanning = false
        log("扫描已停止")
    }

    private fun log(msg: String) {
        runOnUiThread { logText.append(msg + "\n") }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(":") { String.format("%02X", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }
}
