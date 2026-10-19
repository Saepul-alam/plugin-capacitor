package com.al.pluginsecure

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/**
 * Wrapper around the original Capacitor WebViewClient that intercepts
 * HTTP 404/5xx errors, network disconnections, and SSL errors to present
 * a 100% native error screen instead of Chromium web error pages.
 */
class SecureWebViewClient(
    private val delegate: WebViewClient?,
    private val errorOverlay: NativeErrorOverlay
) : WebViewClient() {

    private var hasErrorOnCurrentPage = false

    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return delegate?.shouldOverrideUrlLoading(view, url) ?: super.shouldOverrideUrlLoading(view, url)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return delegate?.shouldOverrideUrlLoading(view, request) ?: super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        return delegate?.shouldInterceptRequest(view, url) ?: super.shouldInterceptRequest(view, url)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        return delegate?.shouldInterceptRequest(view, request) ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        hasErrorOnCurrentPage = false
        delegate?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        delegate?.onPageFinished(view, url)

        if (!hasErrorOnCurrentPage) {
            errorOverlay.hide()
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        delegate?.onReceivedError(view, errorCode, description, failingUrl) ?: super.onReceivedError(view, errorCode, description, failingUrl)

        hasErrorOnCurrentPage = true
        val title = if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
            "Koneksi Terputus"
        } else {
            "Gagal Memuat Halaman"
        }
        val message = "Maaf, halaman tidak dapat dimuat: ${description ?: "terjadi kesalahan jaringan"}"

        errorOverlay.show(
            title = title,
            message = message,
            code = "ERR_$errorCode",
            canRetry = true,
            onRetry = {
                view?.reload()
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error)

        // Only show full error screen if the failure is on the main document frame
        if (request?.isForMainFrame == true) {
            hasErrorOnCurrentPage = true
            val errCode = error?.errorCode ?: -1
            val desc = error?.description?.toString() ?: "Terjadi kesalahan koneksi"

            val title = if (errCode == ERROR_HOST_LOOKUP || errCode == ERROR_CONNECT || errCode == ERROR_TIMEOUT) {
                "Koneksi Terputus"
            } else {
                "Gagal Memuat Halaman"
            }

            errorOverlay.show(
                title = title,
                message = "Tidak dapat memuat halaman: $desc",
                code = "ERR_$errCode",
                canRetry = true,
                onRetry = {
                    view?.reload()
                }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        delegate?.onReceivedHttpError(view, request, errorResponse) ?: super.onReceivedHttpError(view, request, errorResponse)

        if (request?.isForMainFrame == true) {
            val statusCode = errorResponse?.statusCode ?: 200
            if (statusCode >= 400) {
                hasErrorOnCurrentPage = true
                val title = if (statusCode == 404) "Halaman Tidak Ditemukan" else "Terjadi Kesalahan Server"
                val message = if (statusCode == 404) {
                    "Halaman yang Anda tuju tidak ditemukan di server (404 Not Found)."
                } else {
                    "Server sedang mengalami kendala teknis (HTTP $statusCode)."
                }

                errorOverlay.show(
                    title = title,
                    message = message,
                    code = "HTTP $statusCode",
                    canRetry = true,
                    onRetry = {
                        view?.reload()
                    }
                )
            }
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        delegate?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error)

        hasErrorOnCurrentPage = true
        errorOverlay.show(
            title = "Kesalahan Keamanan (SSL)",
            message = "Koneksi ke server tidak aman atau sertifikat tidak valid.",
            code = "SSL_ERROR",
            canRetry = true,
            onRetry = {
                view?.reload()
            }
        )
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload) ?: super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        delegate?.onLoadResource(view, url) ?: super.onLoadResource(view, url)
    }

    override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
        delegate?.onFormResubmission(view, dontResend, resend) ?: super.onFormResubmission(view, dontResend, resend)
    }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
        delegate?.onReceivedHttpAuthRequest(view, handler, host, realm) ?: super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            delegate?.onReceivedClientCertRequest(view, request) ?: super.onReceivedClientCertRequest(view, request)
        }
    }

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        delegate?.onUnhandledKeyEvent(view, event) ?: super.onUnhandledKeyEvent(view, event)
    }

    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
        delegate?.onScaleChanged(view, oldScale, newScale) ?: super.onScaleChanged(view, oldScale, newScale)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return delegate?.onRenderProcessGone(view, detail) ?: super.onRenderProcessGone(view, detail)
        }
        return super.onRenderProcessGone(view, detail)
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        delegate?.onSafeBrowsingHit(view, request, threatType, callback) ?: super.onSafeBrowsingHit(view, request, threatType, callback)
    }
}

