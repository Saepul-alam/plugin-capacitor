package com.al.pluginsecure

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SkeletonActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TARGET_INTENT = "extra_target_intent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skeleton)
        // Retrieve the target intent passed from PluginSecurePlugin
        val targetIntent = intent.getParcelableExtra<Intent>(EXTRA_TARGET_INTENT)
        // Show placeholder for a short period then start the real activity
        Handler(Looper.getMainLooper()).postDelayed({
            targetIntent?.let { startActivity(it) }
            finish()
        }, 300) // 300ms delay – adjust if needed
    }
}

