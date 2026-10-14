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
    val allowFallback: Boolean = false
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
                allowFallback = obj.optBoolean("allowFallback", false)
            )
        }
    }
}