package com.example.itantra.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.itantra.util.Logger
import java.util.UUID

class BleTransport(private val context: Context) {

    companion object {
        // Unique UUID for iTantra service discovery
        val SERVICE_UUID: UUID = UUID.fromString("fa8aed5e-cf3b-46f4-b0f2-f54bc6f0937a")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val advertiser by lazy { adapter?.bluetoothLeAdvertiser }
    private val scanner by lazy { adapter?.bluetoothLeScanner }

    private var isAdvertising = false
    private var isScanning = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Logger.d("BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Logger.e("BLE Advertising failed to start: Error code $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown Device"
            val serviceData = result.scanRecord?.serviceData?.get(ParcelUuid(SERVICE_UUID))
            
            if (serviceData != null) {
                val wifiDirectMac = serviceData.decodeToString()
                Logger.d("Found iTantra Device: $deviceName [$wifiDirectMac]")
                onDeviceFound(deviceName, wifiDirectMac)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Logger.e("BLE Scan failed: Error code $errorCode")
        }
    }

    private var onDeviceFound: (String, String) -> Unit = { _, _ -> }

    fun setOnDeviceFoundListener(listener: (String, String) -> Unit) {
        onDeviceFound = listener
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(wifiDirectMac: String) {
        val currentAdvertiser = advertiser
        if (adapter == null || currentAdvertiser == null) {
            Logger.e("BLE Advertising not supported or Bluetooth OFF")
            return
        }

        if (isAdvertising) {
            Logger.d("BLE Advertising is already running, restarting with fresh MAC")
            stopAdvertising()
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), wifiDirectMac.toByteArray())
            .build()

        Logger.d("Starting BLE Advertising for $wifiDirectMac...")
        currentAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Logger.d("BLE Advertising stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(lowLatency: Boolean = false) {
        val currentScanner = scanner
        if (currentScanner == null) {
            Logger.e("BLE Scanning not supported on this device")
            return
        }

        if (isScanning) {
            Logger.d("BLE Scan is already running")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        Logger.d("Starting BLE Scan (Low Latency: $lowLatency)...")
        currentScanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
            Logger.d("BLE Scan stopped")
        }
    }
}