package com.al.pluginsecure

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Controller for displaying a full native error screen over the WebView.
 * Replaces raw web/Chromium errors (404, connection issues) with a clean native UI.
 */
class NativeErrorOverlay(
    private val activity: Activity,
    private val webView: WebView
) {
    private var errorView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isDisplayed = false

    fun isShowing(): Boolean = isDisplayed

    fun show(
        title: String? = null,
        message: String? = null,
        code: String? = null,
        canRetry: Boolean = true,
        onRetry: (() -> Unit)? = null,
        onBack: (() -> Unit)? = null
    ) {
        mainHandler.post {
            ensureViewAttached()

            val view = errorView ?: return@post
            val tvTitle = view.findViewById<TextView>(R.id.tv_error_title)
            val tvMessage = view.findViewById<TextView>(R.id.tv_error_message)
            val tvCode = view.findViewById<TextView>(R.id.tv_error_code)
            val btnRetry = view.findViewById<Button>(R.id.btn_retry)
            val btnBack = view.findViewById<Button>(R.id.btn_back)
            val progressBar = view.findViewById<ProgressBar>(R.id.pb_error_loading)

            tvTitle.text = title ?: "Halaman Tidak Ditemukan"
            tvMessage.text = message ?: "Maaf, halaman yang Anda tuju tidak dapat dimuat atau koneksi terputus."

            if (!code.isNullOrBlank()) {
                tvCode.visibility = View.VISIBLE
                tvCode.text = "Kode: $code"
            } else {
                tvCode.visibility = View.GONE
            }

            progressBar.visibility = View.GONE
            btnRetry.isEnabled = true
            btnRetry.visibility = if (canRetry) View.VISIBLE else View.GONE

            btnRetry.setOnClickListener {
                progressBar.visibility = View.VISIBLE
                btnRetry.isEnabled = false
                if (onRetry != null) {
                    onRetry()
                } else {
                    // Default retry: reload WebView and try to hide
                    webView.reload()
                }
            }

            btnBack.setOnClickListener {
                if (onBack != null) {
                    onBack()
                } else if (webView.canGoBack()) {
                    hide()
                    webView.goBack()
                } else {
                    hide()
                }
            }

            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1.0f).setDuration(250).start()
            isDisplayed = true
        }
    }

    fun hide() {
        mainHandler.post {
            errorView?.let { view ->
                if (view.visibility == View.VISIBLE) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            view.visibility = View.GONE
                            isDisplayed = false
                        }
                        .start()
                }
            }
        }
    }

    private fun ensureViewAttached() {
        if (errorView != null) return

        val parentGroup = (webView.parent as? ViewGroup)
            ?: activity.findViewById<ViewGroup>(android.R.id.content)

        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.activity_native_error, parentGroup, false)

        val layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.visibility = View.GONE
        parentGroup.addView(view, layoutParams)
        errorView = view
    }
}

