package com.al.pluginsecure

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSObject

class BiometricHelper(private val context: Context) {

    data class BiometricAvailabilityResult(
        val isAvailable: Boolean,
        val biometryType: String,
        val reason: String? = null
    )

    fun checkAvailability(): BiometricAvailabilityResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return BiometricAvailabilityResult(
                isAvailable = false,
                biometryType = "none",
                reason = "Android version does not support biometrics"
            )
        }

        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                BiometricAvailabilityResult(
                    isAvailable = true,
                    biometryType = "fingerprint"
                )
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                BiometricAvailabilityResult(
                    isAvailable = false,
                    biometryType = "none",
                    reason = "No biometric hardware available"
                )
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                BiometricAvailabilityResult(
                    isAvailable = false,
                    biometryType = "none",
                    reason = "Biometric hardware currently unavailable"
                )
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                BiometricAvailabilityResult(
                    isAvailable = false,
                    biometryType = "none",
                    reason = "No biometric credentials enrolled"
                )
            }
            else -> {
                BiometricAvailabilityResult(
                    isAvailable = false,
                    biometryType = "none",
                    reason = "Biometric authentication not supported"
                )
            }
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        options: JSObject?,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder().apply {
            setTitle(options?.getString("title") ?: "Verifikasi Biometrik")
            options?.getString("subtitle")?.let { setSubtitle(it) }
            options?.getString("description")?.let { setDescription(it) }
            setNegativeButtonText(options?.getString("cancelButtonText") ?: "Batal")
        }.build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onComplete(true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onComplete(false, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Prompt remains active for retry, but we don't finish yet
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }
}

