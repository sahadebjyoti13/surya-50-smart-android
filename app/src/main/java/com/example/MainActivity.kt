package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.MyApplicationTheme

class MainActivity : FragmentActivity() {

  private var activeWebView: WebView? = null
  private var nsdListener: NsdManager.DiscoveryListener? = null
  private var nsdManager: NsdManager? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag("surya_main_container"),
          color = Color(0xFF070C18)
        ) {
          SuryaWebAppViewer(this) { webView ->
            activeWebView = webView
          }
        }
      }
    }
  }

  fun requestBiometricAuth(title: String, subtitle: String) {
    runOnUiThread {
      val executor = ContextCompat.getMainExecutor(this)
      val biometricPrompt = BiometricPrompt(
        this,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            Log.d("SURYA_BIOMETRIC", "Biometric authentication succeeded")
            activeWebView?.evaluateJavascript(
              "if (window.onNativeBiometricSuccess) window.onNativeBiometricSuccess();",
              null
            )
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            Log.w("SURYA_BIOMETRIC", "Biometric error [$errorCode]: $errString")
            val escapedErr = errString.toString().replace("'", "\\'")
            activeWebView?.evaluateJavascript(
              "if (window.onNativeBiometricError) window.onNativeBiometricError('$escapedErr', $errorCode);",
              null
            )
          }

          override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
            Log.w("SURYA_BIOMETRIC", "Biometric authentication failed")
            activeWebView?.evaluateJavascript(
              "if (window.onNativeBiometricFailed) window.onNativeBiometricFailed();",
              null
            )
          }
        }
      )

      val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title.ifEmpty { "SURYA-50 SMU Security" })
        .setSubtitle(subtitle.ifEmpty { "Verify Fingerprint or Face to unlock controls" })
        .setAllowedAuthenticators(
          BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

      try {
        biometricPrompt.authenticate(promptInfo)
      } catch (e: Exception) {
        Log.e("SURYA_BIOMETRIC", "Failed to trigger BiometricPrompt: ${e.message}", e)
        activeWebView?.evaluateJavascript(
          "if (window.onNativeBiometricError) window.onNativeBiometricError('${e.message}', -1);",
          null
        )
      }
    }
  }

  fun discoverController() {
    runOnUiThread {
      val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
      nsdManager = manager
      if (nsdListener != null) { try { manager.stopServiceDiscovery(nsdListener) } catch (_: Exception) {} }
      val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { }
        override fun onServiceFound(service: NsdServiceInfo) {
          if (!service.serviceType.contains("_surya._tcp")) return
          manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
              activeWebView?.evaluateJavascript("window.onNativeDiscoveryResult && window.onNativeDiscoveryResult(null, 'Resolve failed: $errorCode')", null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
              val host = info.host?.hostAddress ?: return
              val path = try { info.attributes["path"]?.toString(Charsets.UTF_8) ?: "/ws" } catch (_: Exception) { "/ws" }
              val url = "ws://$host:${info.port}$path"
              val safe = org.json.JSONObject.quote(url)
              activeWebView?.evaluateJavascript("window.onNativeDiscoveryResult && window.onNativeDiscoveryResult($safe, null)", null)
              try { manager.stopServiceDiscovery(nsdListener) } catch (_: Exception) { }
            }
          })
        }
        override fun onServiceLost(service: NsdServiceInfo) { }
        override fun onDiscoveryStopped(type: String) { }
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
          activeWebView?.evaluateJavascript("window.onNativeDiscoveryResult && window.onNativeDiscoveryResult(null, 'Discovery failed: $errorCode')", null)
          try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
        }
        override fun onStopDiscoveryFailed(type: String, errorCode: Int) { }
      }
      nsdListener = listener
      manager.discoverServices("_surya._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
  }

  fun checkBiometricStatus(): Boolean {
    val biometricManager = BiometricManager.from(this)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
      BiometricManager.Authenticators.BIOMETRIC_WEAK or
      BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val result = biometricManager.canAuthenticate(authenticators)
    return result == BiometricManager.BIOMETRIC_SUCCESS
  }
}

class SuryaBiometricBridge(private val activity: MainActivity) {

  private val prefs = activity.getSharedPreferences("surya_persistent_store", Context.MODE_PRIVATE)

  @JavascriptInterface
  fun discoverController() { activity.discoverController() }

  @JavascriptInterface
  fun isBiometricHardwareAvailable(): Boolean {
    return activity.checkBiometricStatus()
  }

  @JavascriptInterface
  fun triggerBiometricAuth(title: String?, subtitle: String?) {
    activity.requestBiometricAuth(title ?: "", subtitle ?: "")
  }

  // Persistent storage methods across logins/logouts
  @JavascriptInterface
  fun persistData(key: String, value: String) {
    try {
      prefs.edit().putString(key, value).apply()
      Log.d("SURYA_STORAGE", "Persisted key '$key' successfully (${value.length} chars)")
    } catch (e: Exception) {
      Log.e("SURYA_STORAGE", "Error persisting key '$key': ${e.message}")
    }
  }

  @JavascriptInterface
  fun loadPersistedData(key: String): String {
    return prefs.getString(key, "") ?: ""
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SuryaWebAppViewer(
  activity: MainActivity,
  onWebViewReady: (WebView) -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF070C18))
  ) {
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("surya_webview"),
      factory = { context ->
        WebView(context).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          setBackgroundColor(android.graphics.Color.parseColor("#070C18"))

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          }

          addJavascriptInterface(SuryaBiometricBridge(activity), "AndroidBiometric")
          addJavascriptInterface(SuryaBiometricBridge(activity), "AndroidSurya")

          webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              Log.d(
                "SURYA_50_PWA",
                "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
              )
              return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              request?.grant(request.resources)
            }
          }

          webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              Log.i("SURYA_50_PWA", "Page loaded successfully: $url")
            }
          }

          loadUrl("file:///android_asset/index.html")
          onWebViewReady(this)
        }
      }
    )
  }
}
