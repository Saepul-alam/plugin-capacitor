package com.al.pluginsecure

import com.getcapacitor.JSObject
import org.json.JSONObject

data class VerifiedPhotoOptions(
    val quality: Int = 90,
    val direction: String = "rear",
    val requireGPSVerification: Boolean = true,
    val maxGPSAgeMs: Long = 5000,
    val minAccuracyMeters: Float = 50f,
    val embedEXIF: Boolean = true,
    val watermark: Boolean = false,
    val resultType: String = "uri",
    val allowFallback: Boolean = false,
    val burstCount: Int = 1,
    val burstIntervalMs: Long = 250L
) {
    companion object {
        fun fromJson(json: String): VerifiedPhotoOptions {
            val obj = JSONObject(json)
            return VerifiedPhotoOptions(
                quality = obj.optInt("quality", 90),
                direction = obj.optString("direction", "rear"),
                requireGPSVerification = obj.optBoolean("requireGPSVerification", true),
                maxGPSAgeMs = obj.optLong("maxGPSAgeMs", 5000),
                minAccuracyMeters = obj.optDouble("minAccuracyMeters", 50.0).toFloat(),
                embedEXIF = obj.optBoolean("embedEXIF", true),
                watermark = obj.optBoolean("watermark", false),
                resultType = obj.optString("resultType", "uri"),
                allowFallback = obj.optBoolean("allowFallback", false),
                burstCount = obj.optInt("burstCount", 1),
                burstIntervalMs = obj.optLong("burstIntervalMs", 250L)
            )
        }
    }
}