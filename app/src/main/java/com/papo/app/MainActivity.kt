package com.papo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.app.Activity
import android.view.ViewGroup

class MainActivity : Activity() {

    companion object {
        private const val WEBSITE_URL = "https://hjhjh-ruby.vercel.app/"
        private const val WEBSITE_HOST = "hjhjh-ruby.vercel.app"
        private const val REQUEST_RECORD_AUDIO = 701
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var pendingMediaPermissionRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val container = FrameLayout(this)
        container.setBackgroundColor(Color.WHITE)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(3)
        )

        container.addView(webView)
        container.addView(progressBar)
        setContentView(container)

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.setSupportMultipleWindows(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webSettings.safeBrowsingEnabled = true
        }

        webView.setBackgroundColor(Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (isTrustedWebsite(uri)) return false
                openExternal(uri)
                return true
            }

            @Deprecated("Deprecated in Android API")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = runCatching { Uri.parse(url ?: "") }.getOrNull() ?: return false
                if (isTrustedWebsite(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 4
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.progress = 100
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Não foi possível carregar o Papo. Verifique sua conexão.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    val resources = request.resources?.toList() ?: emptyList()
                    val onlyAudioCapture = resources.isNotEmpty() &&
                        resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }

                    if (!isTrustedWebsite(request.origin) || !onlyAudioCapture) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMediaPermissionRequest = request
                        requestPermissions(
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            REQUEST_RECORD_AUDIO
                        )
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingMediaPermissionRequest === request) {
                    pendingMediaPermissionRequest = null
                }
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(WEBSITE_URL)
        }
    }

    private fun isTrustedWebsite(uri: Uri): Boolean {
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(WEBSITE_HOST, ignoreCase = true)
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http" && scheme != "mailto" && scheme != "tel") {
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não há um aplicativo para abrir este link.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return

        val pendingRequest = pendingMediaPermissionRequest
        pendingMediaPermissionRequest = null
        if (pendingRequest == null) return

        val resources = pendingRequest.resources?.toList() ?: emptyList()
        val validRequest = isTrustedWebsite(pendingRequest.origin) &&
            resources.isNotEmpty() &&
            resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (validRequest && granted) {
            pendingRequest.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingRequest.deny()
            Toast.makeText(this, "Permita o microfone para fazer chamadas de voz.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pendingMediaPermissionRequest?.deny()
        pendingMediaPermissionRequest = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
