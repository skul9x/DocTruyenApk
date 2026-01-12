package com.skul9x.doctruyen.network

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
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
        
        // Make dialog invisible to user - 1x1 pixel, no dim
        dialog.window?.apply {
            setLayout(1, 1)
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
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
                    // Successfully verified - silently
                    RetrofitClient.saveCookie(context, cookies)
                    dialog.dismiss()
                    onResult(true)
                } else if (verificationAttempts >= 5) {
                    // Timeout - silent failure
                    dialog.dismiss()
                    onResult(false)
                }
                // Otherwise wait for next page load
            }
            
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                dialog.dismiss()
                onResult(false)
            }
        }
        
        webView.loadUrl(url)
        dialog.show()
    }
}
