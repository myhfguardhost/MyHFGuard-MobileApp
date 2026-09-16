package com.vitalink.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Required by Health Connect on Android 13 and lower.
 *
 * Health Connect opens this Activity when it needs to show the app's
 * permission rationale / privacy policy page.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = false
            loadUrl("https://myhfguardhost.github.io/MyHFGuard/privacy")
        }

        setContentView(webView)
    }
}
