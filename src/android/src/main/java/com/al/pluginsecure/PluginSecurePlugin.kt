package com.al.pluginsecure

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.getcapacitor.JSObject
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

@NativePlugin
class PluginSecurePlugin : Plugin() {
    private val gpsHelper = GPSVerificationHelper(context)
    private var monitoringJob: Job? = null
    private var cameraCall: PluginCall? = null
    private val REQUEST_CODE_CAMERA = 1001

    @PluginMethod
    fun verifyGPS(call: PluginCall) {
        val result = gpsHelper.verifyGPS()
        call.resolve(result.details)
    }

    @PluginMethod
    fun checkDeviceIntegrity(call: PluginCall) {
        val result = gpsHelper.checkDeviceIntegrity()
        call.resolve(JSObject().apply {
            put("isRooted", result.isRooted)
            put("isJailbroken", result.isJailbroken)
            put("details", result.details)
        })
    }

    @PluginMethod
    fun captureVerifiedPhoto(call: PluginCall) {
        cameraCall = call
        val intent = Intent(activity, CameraVerifiedActivity::class.java).apply {
            putExtra("options", call.options.toString())
        }
        startActivityForResult(call, intent, REQUEST_CODE_CAMERA)
    }

    @PluginMethod
    fun startGPSMonitoring(call: PluginCall) {
        val intervalMs = call.getInt("intervalMs", 30000)
        monitoringJob?.cancel()

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val result = gpsHelper.verifyGPS()
                if (result.isMock) {
                    notifyListeners("gpsMockDetected", result.details)
                }
                kotlinx.coroutines.delay(intervalMs.toLong())
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun stopGPSMonitoring(call: PluginCall) {
        monitoringJob?.cancel()
        monitoringJob = null
        call.resolve()
    }
}