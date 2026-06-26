package com.tennisbird.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class WebViewActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingDownload: PendingDownload? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
            ?.trim()
            ?.takeIf(WebUrlStore::isWebUrl)
            ?: WebUrlStore.getCachedUrl(this)

        if (url == null) {
            finish()
            return
        }

        WebUrlStore.saveUrl(this, url)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setContentView(webView)
        configureWebView()
        webView.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            saveCurrentPage()
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            saveCurrentPage()
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            webView.postDelayed({ saveCurrentPage() }, SAVE_AFTER_BACK_DELAY_MS)
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                saveUrl(url)
                injectClipboardBridge()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                saveUrl(url ?: view.url)
                injectClipboardBridge()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleExternalUrl(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleExternalUrl(Uri.parse(url))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                openFileChooser(fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermissionRequest(request) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                runOnUiThread { handleGeolocationPermissionRequest(origin, callback) }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                // Some WebView versions call this while the Android runtime permission dialog is
                // still open. Do not deny here; the final decision is delivered in
                // onRequestPermissionsResult.
            }
        }

        webView.addJavascriptInterface(ClipboardBridge(), CLIPBOARD_BRIDGE_NAME)
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun injectClipboardBridge() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(CLIPBOARD_BRIDGE_SCRIPT, null)
    }

    private fun saveCurrentPage() {
        if (!::webView.isInitialized) return
        saveUrl(webView.url)
    }

    private fun saveUrl(url: String?) {
        val normalized = url?.trim().orEmpty()
        if (WebUrlStore.isWebUrl(normalized)) {
            WebUrlStore.saveUrl(this, normalized)
        }
    }

    private fun handleExternalUrl(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        if (scheme == "http" || scheme == "https") return false

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }

    private fun openFileChooser(params: WebChromeClient.FileChooserParams) {
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = resolveChooserMimeType(params.acceptTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val cameraIntent = createCameraIntent()
        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        try {
            startActivityForResult(chooser, REQUEST_FILE_CHOOSER)
        } catch (_: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }

    private fun resolveChooserMimeType(acceptTypes: Array<String>): String {
        val firstType = acceptTypes.firstOrNull { it.isNotBlank() }?.trim()
        return when {
            firstType.isNullOrBlank() -> "*/*"
            firstType.contains(",") -> firstType.substringBefore(",").trim().ifBlank { "*/*" }
            else -> firstType
        }
    }

    private fun createCameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null

        val imageFile = runCatching {
            val directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
            if (!directory.exists()) directory.mkdirs()
            File.createTempFile("webview_${UUID.randomUUID()}", ".jpg", directory)
        }.getOrNull() ?: return null

        cameraImageUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            imageFile,
        )

        return intent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_CHOOSER) return

        val result = if (resultCode == RESULT_OK) {
            collectChosenFiles(data)
        } else {
            null
        }

        fileCallback?.onReceiveValue(result)
        fileCallback = null
        cameraImageUri = null
    }

    private fun collectChosenFiles(data: Intent?): Array<Uri>? {
        data?.clipData?.let { clip ->
            return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        }

        data?.data?.let { return arrayOf(it) }
        cameraImageUri?.let { return arrayOf(it) }
        return null
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val requested = request.resources.toSet()
        val needsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested
        val needsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested
        val missingPermissions = buildList {
            if (needsCamera && checkSelfPermissionCompat(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (needsAudio && checkSelfPermissionCompat(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }

        if (missingPermissions.isEmpty()) {
            request.grant(request.resources)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingPermissionRequest = request
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_WEB_PERMISSIONS)
        } else {
            request.deny()
        }
    }

    private fun handleGeolocationPermissionRequest(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (hasLocationPermission()) {
            callback.invoke(origin, true, false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingGeolocationOrigin = origin
            pendingGeolocationCallback = callback
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_GEOLOCATION_PERMISSION,
            )
        } else {
            callback.invoke(origin, true, false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_DOWNLOAD_PERMISSION) {
            val download = pendingDownload
            pendingDownload = null
            if (download != null && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                downloadFile(
                    url = download.url,
                    userAgent = download.userAgent,
                    contentDisposition = download.contentDisposition,
                    mimeType = download.mimeType,
                )
            } else {
                Toast.makeText(this, "Download permission denied", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (requestCode == REQUEST_GEOLOCATION_PERMISSION) {
            val origin = pendingGeolocationOrigin
            val callback = pendingGeolocationCallback
            pendingGeolocationOrigin = null
            pendingGeolocationCallback = null

            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED } || hasLocationPermission()
            callback?.invoke(origin ?: webView.url.orEmpty(), granted, false)
            return
        }

        if (requestCode != REQUEST_WEB_PERMISSIONS) return

        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            request.grant(request.resources)
        } else {
            request.deny()
        }
    }

    private fun checkSelfPermissionCompat(permission: String): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(permission)
        } else {
            PackageManager.PERMISSION_GRANTED
        }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermissionCompat(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun downloadFile(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermissionCompat(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_DOWNLOAD_PERMISSION,
                )
            }
            return
        }

        val uri = Uri.parse(url)
        val resolvedMimeType = mimeType
            ?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
            ?: "application/octet-stream"
        val filename = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType)

        try {
            val request = DownloadManager.Request(uri)
                .setMimeType(resolvedMimeType)
                .setTitle(filename)
                .setDescription(uri.host.orEmpty())
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("User-Agent", userAgent)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not download file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    private inner class ClipboardBridge {
        @JavascriptInterface
        fun writeText(text: String?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text.orEmpty()))
        }

        @JavascriptInterface
        fun readText(): String {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return ""
            return clip.getItemAt(0)?.coerceToText(this@WebViewActivity)?.toString().orEmpty()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val CLIPBOARD_BRIDGE_NAME = "TennisBirdClipboard"
        private const val CLIPBOARD_LABEL = "Tennis Bird"
        private const val SAVE_AFTER_BACK_DELAY_MS = 250L
        private const val REQUEST_FILE_CHOOSER = 2401
        private const val REQUEST_WEB_PERMISSIONS = 2402
        private const val REQUEST_DOWNLOAD_PERMISSION = 2403
        private const val REQUEST_GEOLOCATION_PERMISSION = 2404
        private val CLIPBOARD_BRIDGE_SCRIPT = """
            (function() {
              if (!window.TennisBirdClipboard) return;
              var clipboard = {
                writeText: function(text) {
                  return new Promise(function(resolve, reject) {
                    try {
                      window.TennisBirdClipboard.writeText(String(text == null ? '' : text));
                      resolve();
                    } catch (error) {
                      reject(error);
                    }
                  });
                },
                readText: function() {
                  return new Promise(function(resolve, reject) {
                    try {
                      resolve(String(window.TennisBirdClipboard.readText() || ''));
                    } catch (error) {
                      reject(error);
                    }
                  });
                }
              };
              try {
                Object.defineProperty(navigator, 'clipboard', {
                  configurable: true,
                  enumerable: true,
                  value: clipboard
                });
              } catch (error) {
                navigator.clipboard = clipboard;
              }
            })();
        """.trimIndent()
    }
}
