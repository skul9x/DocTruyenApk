package com.skul9x.doctruyen.network

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.skul9x.doctruyen.R

/**
 * Host Verification for free.nf hosting
 * Pattern adapted from LocateShare-main
 * 
 * free.nf uses cookie-based verification, this class handles:
 * 1. Opens a WebView to load the hosting URL
 * 2. Waits for the __test cookie to be set
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
        
        val webView = dialog.findViewById<WebView>(R.id.webView)
        
        if (webView == null) {
            Toast.makeText(context, "Lỗi: Không tìm thấy WebView!", Toast.LENGTH_SHORT).show()
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
        
        // DO NOT Clear cookies - we want to keep them if they exist
        // CookieManager.getInstance().removeAllCookies(null)
        
        webView.webViewClient = object : WebViewClient() {
            var verificationAttempts = 0
            
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                super.onPageFinished(view, loadedUrl)
                verificationAttempts++
                
                val cookies = CookieManager.getInstance().getCookie(loadedUrl)
                com.skul9x.doctruyen.utils.DebugLogger.log("HostingVerifier", "Page Loaded: $loadedUrl\nCookies: $cookies", com.skul9x.doctruyen.utils.LogType.INFO)

                if (cookies != null && cookies.contains("__test")) {
                    // Successfully verified!
                    RetrofitClient.cookie = cookies
                    Toast.makeText(context, "✅ Xác thực thành công!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onResult(true)
                } else if (verificationAttempts < 3) {
                    // Give it a moment for JS to run and reload
                    // The aes.js script redirects, so we might just wait for the next onPageFinished
                    Toast.makeText(context, "⏳ Đang chờ xác thực...", Toast.LENGTH_SHORT).show()
                } else {
                    // Failed after attempts
                    if (verificationAttempts >= 5) { // Timeout
                         dialog.dismiss()
                         onResult(false)
                         Toast.makeText(context, "⚠️ Không thể xác thực hosting!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Toast.makeText(context, "❌ Lỗi kết nối: $description", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                onResult(false)
            }
        }
        
        webView.loadUrl(url)
        dialog.show()
        Toast.makeText(context, "🔄 Đang xác thực hosting...", Toast.LENGTH_LONG).show()
    }
}
