package com.example.itantra.deviceprofile

import android.app.ActivityManager
import android.content.Context
import com.example.itantra.util.Logger

class DeviceProfiler(private val context: Context) {
    
    data class DeviceInfo(
        val totalRamMb: Long,
        val availableProcessors: Int,
        val isLowSpec: Boolean
    )

    fun getDeviceInfo(): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
        val processors = Runtime.getRuntime().availableProcessors()
        
        // Thresholds based on spec: 2GB RAM (2048MB)
        val isLowSpec = totalRamMb < 3000 || processors <= 4
        
        val info = DeviceInfo(totalRamMb, processors, isLowSpec)
        
        Logger.d("Device Profiling:")
        Logger.d("- Total RAM: ${totalRamMb}MB")
        Logger.d("- CPU Cores: $processors")
        Logger.d("- Low Spec Mode: ${if (isLowSpec) "ENABLED" else "DISABLED"}")
        
        return info
    }
}