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
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val WEBSITE_URL = "https://hjhjh-ruby.vercel.app/"
        private const val WEBSITE_HOST = "hjhjh-ruby.vercel.app"
        private const val REQUEST_RECORD_AUDIO = 701
        private const val NATIVE_AUTH_OBJECT = "papoNativeAuth"
        private const val GOOGLE_SIGN_IN_MESSAGE = "googleSignIn"
        private const val GOOGLE_TOKEN_EVENT = "papo-native-google-id-token"
        private const val GOOGLE_ERROR_EVENT = "papo-native-google-auth-error"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val credentialManager by lazy { CredentialManager.create(this) }
    private var pendingMediaPermissionRequest: PermissionRequest? = null
    private var nativeGoogleSignInInProgress = false

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
        configureNativeAuthBridge()

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

    /**
     * Exposes one narrowly scoped message listener to the trusted top-level Papo origin.
     * Unlike addJavascriptInterface, WebMessageListener lets us validate both origin and frame.
     */
    private fun configureNativeAuthBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(
                this,
                "Atualize o Android System WebView para habilitar o login Google nativo.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            NATIVE_AUTH_OBJECT,
            setOf("https://$WEBSITE_HOST"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame || !isTrustedWebsite(sourceOrigin)) return
                    if (message.data == GOOGLE_SIGN_IN_MESSAGE) {
                        runOnUiThread { startNativeGoogleSignIn() }
                    }
                }
            }
        )
    }

    private fun startNativeGoogleSignIn() {
        if (nativeGoogleSignInInProgress) return
        nativeGoogleSignInInProgress = true

        lifecycleScope.launch {
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(getString(com.papo.app.R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    context = this@MainActivity,
                    request = request
                )
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val detail = JSONObject().put("idToken", googleCredential.idToken)
                    dispatchNativeAuthEvent(GOOGLE_TOKEN_EVENT, detail)
                } else {
                    dispatchNativeAuthError("O Google não retornou uma credencial compatível.")
                }
            } catch (error: Exception) {
                // Do not expose tokens or internal exception details to the page.
                val canceled = error.javaClass.simpleName.contains("Cancellation", ignoreCase = true)
                dispatchNativeAuthError(
                    if (canceled) "Login cancelado."
                    else "Não foi possível concluir o login com Google. Verifique a configuração OAuth do app e tente novamente."
                )
            } finally {
                nativeGoogleSignInInProgress = false
            }
        }
    }

    private fun dispatchNativeAuthError(message: String) {
        dispatchNativeAuthEvent(GOOGLE_ERROR_EVENT, JSONObject().put("message", message))
    }

    private fun dispatchNativeAuthEvent(eventName: String, detail: JSONObject) {
        val script = "window.dispatchEvent(new CustomEvent(${JSONObject.quote(eventName)}, {detail: $detail}));"
        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
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

    @Deprecated("Android compatibility")
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
