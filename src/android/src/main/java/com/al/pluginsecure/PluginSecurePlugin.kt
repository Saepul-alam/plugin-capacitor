package com.al.pluginsecure

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSObject
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@NativePlugin(
    name = "PluginSecure",
    requestCodes = [
        PluginSecurePlugin.REQUEST_CODE_CAMERA,
        PluginSecurePlugin.REQUEST_CODE_BARCODE
    ]
)
class PluginSecurePlugin : Plugin() {
    companion object {
        const val REQUEST_CODE_CAMERA = 1001
        const val REQUEST_CODE_BARCODE = 1002
    }

    private val gpsHelper by lazy { GPSVerificationHelper(context) }
    private val biometricHelper by lazy { BiometricHelper(context) }
    private var monitoringJob: Job? = null

    @PluginMethod
    fun verifyGPS(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = gpsHelper.verifyGPS()
                val ret = JSObject().apply {
                    put("isMock", result.isMock)
                    put("isJailbroken", result.isJailbroken)
                    put("details", result.details)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("GPS verification failed: ${e.message}", e)
            }
        }
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
        saveCall(call)
        val intent = Intent(activity, CameraVerifiedActivity::class.java).apply {
            putExtra("options", call.data.toString())
        }
        startActivityForResult(call, intent, REQUEST_CODE_CAMERA)
    }

    @PluginMethod
    fun checkBiometricAvailability(call: PluginCall) {
        val avail = biometricHelper.checkAvailability()
        call.resolve(JSObject().apply {
            put("isAvailable", avail.isAvailable)
            put("biometryType", avail.biometryType)
            put("reason", avail.reason)
        })
    }

    @PluginMethod
    fun authenticateBiometric(call: PluginCall) {
        val act = activity as? FragmentActivity
        if (act == null) {
            call.reject("Activity does not support FragmentActivity")
            return
        }

        act.runOnUiThread {
            biometricHelper.authenticate(act, call.data) { success, error ->
                if (success) {
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } else {
                    call.resolve(JSObject().apply {
                        put("success", false)
                        put("error", error ?: "Authentication failed")
                    })
                }
            }
        }
    }

    @PluginMethod
    fun scanBarcode(call: PluginCall) {
        saveCall(call)
        val intent = Intent(activity, BarcodeScanActivity::class.java).apply {
            putExtra("prompt", call.getString("prompt"))
        }
        startActivityForResult(call, intent, REQUEST_CODE_BARCODE)
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        val savedCall = savedCall ?: return

        when (requestCode) {
            REQUEST_CODE_CAMERA -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val resultStr = data.getStringExtra("result") ?: "{}"
                    try {
                        val jsObj = JSObject(resultStr)
                        savedCall.resolve(jsObj)
                    } catch (e: Exception) {
                        savedCall.reject("Failed to parse camera result: ${e.message}")
                    }
                } else {
                    val error = data?.getStringExtra("error") ?: "Photo capture cancelled"
                    savedCall.reject(error)
                }
                freeSavedCall()
            }
            REQUEST_CODE_BARCODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val resultStr = data.getStringExtra("result") ?: "{}"
                    try {
                        val jsObj = JSObject(resultStr)
                        savedCall.resolve(jsObj)
                    } catch (e: Exception) {
                        savedCall.reject("Failed to parse barcode result: ${e.message}")
                    }
                } else {
                    savedCall.resolve(JSObject().apply {
                        put("hasContent", false)
                        put("content", "")
                        put("format", "NONE")
                    })
                }
                freeSavedCall()
            }
        }
    }

    @PluginMethod
    fun startGPSMonitoring(call: PluginCall) {
        val intervalMs = call.getInt("intervalMs", 30000) ?: 30000
        monitoringJob?.cancel()

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val result = gpsHelper.verifyGPS()
                    if (result.isMock) {
                        val ret = JSObject().apply {
                            put("isMock", result.isMock)
                            put("isJailbroken", result.isJailbroken)
                            put("details", result.details)
                        }
                        notifyListeners("gpsMockDetected", ret)
                    }
                } catch (_: Exception) {}
                delay(intervalMs.toLong())
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun stopGPSMonitoring(call: PluginCall) {
        monitoringJob?.cancel()
        monitoringJob = null
        gpsHelper.unregisterGnssListener()
        call.resolve()
    }

    override fun handleOnDestroy() {
        monitoringJob?.cancel()
        monitoringJob = null
        gpsHelper.unregisterGnssListener()
        super.handleOnDestroy()
    }
}