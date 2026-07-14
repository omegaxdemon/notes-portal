package com.dc.dailynotescmsa

import android.widget.ProgressBar
import android.content.Context
import android.net.*
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var offlineLayout: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var retryLoader: ProgressBar

    private var lastDownloadId: Long = -1L
    private var lastDownloadedFileName = ""

    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {

            runOnUiThread {
                // Wait for user to press Retry
            }

        }

        override fun onLost(network: Network) {

            runOnUiThread {

                swipeRefresh.visibility = View.GONE
                offlineLayout.visibility = View.VISIBLE

            }

        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )

            }

        }
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->

                if (!task.isSuccessful) {
                    Log.e("FCM", "Fetching token failed", task.exception)
                    return@addOnCompleteListener
                }

                Log.d("FCM_TOKEN", task.result)
            }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        offlineLayout = findViewById(R.id.offlineLayout)
        retryButton = findViewById(R.id.retryButton)
        retryLoader = findViewById(R.id.retryLoader)

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        //-------------------------------------------------
        // Register Network Callback
        //-------------------------------------------------

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        //-------------------------------------------------
        // WebView Settings
        //-------------------------------------------------

        with(webView.settings) {

            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = true

            useWideViewPort = true
            loadWithOverviewMode = true

            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            mediaPlaybackRequiresUserGesture = false

        }

        //-------------------------------------------------
        // WebView Client
        //-------------------------------------------------

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {

                super.onPageFinished(view, url)

                swipeRefresh.isRefreshing = false
                retryLoader.visibility = View.GONE

                retryButton.visibility = View.VISIBLE
                retryButton.isEnabled = true

                offlineLayout.visibility = View.GONE
                swipeRefresh.visibility = View.VISIBLE

            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {

                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {

                    swipeRefresh.visibility = View.GONE
                    offlineLayout.visibility = View.VISIBLE

                }

            }

        }

        webView.webChromeClient = WebChromeClient()

        //-------------------------------------------------
// Native Download Manager
//-------------------------------------------------

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->

            android.util.Log.d("DOWNLOAD", "URL = $url")
            android.util.Log.d("DOWNLOAD", "Content-Disposition = $contentDisposition")
            android.util.Log.d("DOWNLOAD", "MimeType = $mimeType")

            val request = DownloadManager.Request(Uri.parse(url))

            val fileName = Regex("filename=\"([^\"]+)\"")
                .find(contentDisposition ?: "")
                ?.groupValues
                ?.get(1)
                ?: URLUtil.guessFileName(url, contentDisposition, mimeType)

            val extension = fileName.substringAfterLast('.', "").lowercase()

            val realMimeType = when (extension) {
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "zip" -> "application/zip"
                "rar" -> "application/vnd.rar"
                "7z" -> "application/x-7z-compressed"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> mimeType ?: "application/octet-stream"
            }

            request.setMimeType(realMimeType)

            request.addRequestHeader("User-Agent", userAgent)

            request.setDescription("Downloading file...")

//            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
//            val fileName = Regex("filename=\"([^\"]+)\"")
//                .find(contentDisposition ?: "")
//                ?.groupValues
//                ?.get(1)
//                ?: URLUtil.guessFileName(url, contentDisposition, mimeType)

            request.setTitle(fileName)

            request.allowScanningByMediaScanner()

            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            lastDownloadId= dm.enqueue(request)
            lastDownloadedFileName = fileName
            Toast.makeText(
                this,
                "$fileName downloading...",
                Toast.LENGTH_SHORT
            ).show()

            //dm.enqueue(request)
            //webView.stopLoading()
//            Toast.makeText(
//                this,
//                "$fileName downloaded in Downloads Folder!",
//                Toast.LENGTH_SHORT
//            ).show()

        }

        //-------------------------------------------------
        // Pull To Refresh
        //-------------------------------------------------

        swipeRefresh.setOnRefreshListener {

            webView.reload()

        }

        //-------------------------------------------------
        // Initial Internet Check
        //-------------------------------------------------

        if (isInternetAvailable()) {

            swipeRefresh.visibility = View.VISIBLE
            offlineLayout.visibility = View.GONE

            webView.loadUrl("https://dailynotescmsa.netlify.app")

        } else {

            swipeRefresh.visibility = View.GONE
            offlineLayout.visibility = View.VISIBLE

        }

        //-------------------------------------------------
        // Retry
        //-------------------------------------------------

        retryButton.setOnClickListener {

            retryButton.isEnabled = false
            retryLoader.visibility = View.VISIBLE

            if (isInternetAvailable()) {

                retryButton.visibility = View.GONE
                retryLoader.visibility = View.VISIBLE

                webView.loadUrl("https://dailynotescmsa.netlify.app")

            } else {

                retryLoader.visibility = View.GONE
                retryButton.isEnabled = true

            }

        }

        //-------------------------------------------------
// Download Complete Receiver
//-------------------------------------------------

        val downloadReceiver = object : BroadcastReceiver() {

            override fun onReceive(context: Context?, intent: Intent?) {

                Toast.makeText(
                    this@MainActivity,
                    "Receiver Triggered",
                    Toast.LENGTH_SHORT
                ).show()

                val id = intent?.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    -1
                ) ?: return

                if (id != lastDownloadId) return

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download Complete")
                    .setMessage("$lastDownloadedFileName has been downloaded successfully.")
                    .setPositiveButton("Open") { _, _ ->

                        val downloadManager =
                            getSystemService(DOWNLOAD_SERVICE) as DownloadManager

                        val uri: Uri? =
                            downloadManager.getUriForDownloadedFile(lastDownloadId)

                        if (uri != null) {

                            val openIntent = Intent(Intent.ACTION_VIEW)

                            openIntent.setDataAndType(uri, null)

                            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                            startActivity(openIntent)

                        }

                    }
                    .setNegativeButton("Close", null)
                    .show()

            }

        }
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )


        //-------------------------------------------------
        // Back Button
        //-------------------------------------------------

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    if (webView.canGoBack()) {

                        webView.goBack()

                    } else {

                        finish()

                    }

                }

            }

        )

    }

    //-------------------------------------------------
    // Internet Check
    //-------------------------------------------------

    private fun isInternetAvailable(): Boolean {

        val network =
            connectivityManager.activeNetwork ?: return false

        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    }

    //-------------------------------------------------
    // Cleanup
    //-------------------------------------------------

    override fun onDestroy() {

        super.onDestroy()

        connectivityManager.unregisterNetworkCallback(networkCallback)

    }

}