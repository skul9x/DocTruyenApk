package com.skul9x.doctruyen.network

import android.annotation.TargetApi
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.skul9x.doctruyen.R

/**
 * Host Verification for free.nf hosting
 * Pattern adapted from LocateShare-main
 * 
 * free.nf uses cookie-based verification, this class handles:
 * 1. Opens a hidden WebView to load the hosting URL
 * 2. Waits for the __test cookie to be set (silently)
 * 3. Stores the cookie for subsequent API requests
 */
object HostingVerifier {
    
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    
    fun verify(context: Context, url: String, onResult: (Boolean) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_webview)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)
        
        // Set appropriate size so Chromium engine renders and executes JS without throttling
        dialog.window?.apply {
            val width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.2f)
        }
        
        val webView = dialog.findViewById<WebView>(R.id.webView)
        
        if (webView == null) {
            onResult(false)
            return
        }
        
        // Configure WebView settings
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        
        webView.webViewClient = object : WebViewClient() {
            var verificationAttempts = 0
            
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                super.onPageFinished(view, loadedUrl)
                verificationAttempts++
                
                val cookies = CookieManager.getInstance().getCookie(loadedUrl)
                com.skul9x.doctruyen.utils.DebugLogger.log("HostingVerifier", "Page Loaded: $loadedUrl\nCookies: $cookies", com.skul9x.doctruyen.utils.LogType.INFO)

                if (cookies != null && cookies.contains("__test")) {
                    CookieManager.getInstance().flush()
                    // Successfully verified
                    RetrofitClient.saveCookie(context, cookies)
                    if (dialog.isShowing) {
                        try { dialog.dismiss() } catch (ignored: Exception) {}
                    }
                    onResult(true)
                } else if (verificationAttempts >= 8) {
                    // Timeout
                    if (dialog.isShowing) {
                        try { dialog.dismiss() } catch (ignored: Exception) {}
                    }
                    onResult(false)
                }
                // Otherwise wait for next page load
            }
            
            @TargetApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    if (dialog.isShowing) {
                        try { dialog.dismiss() } catch (ignored: Exception) {}
                    }
                    onResult(false)
                }
            }
            
            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (dialog.isShowing) {
                    try { dialog.dismiss() } catch (ignored: Exception) {}
                }
                onResult(false)
            }
        }
        
        webView.loadUrl(url)
        dialog.show()
    }
}
