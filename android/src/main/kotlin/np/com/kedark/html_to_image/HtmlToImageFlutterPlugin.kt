package codingislife.htmltoimageflutter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue

class HtmlToImageFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var activity: Activity
    private lateinit var context: Context
    private lateinit var webView: WebView

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "html_to_image_flutter")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val method = call.method
        val arguments = call.arguments as Map<*, *>
        val rawContent = arguments["content"] as String
        val delay = arguments["delay"] as Int? ?: 500 // Increased default delay for reliability
        val width = arguments["width"] as Int?

        if (method == "convertToImage") {
            webView = WebView(context).apply {
                // Enable additional WebView settings for complex content
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                setInitialScale(100) // Ensure 1:1 scale for accurate rendering
            }
            WebView.enableSlowWholeDocumentDraw()

            val displaySize = getDisplaySize()
            val targetWidth = width ?: displaySize.width

            val fullHtml = """
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            max-width: ${targetWidth}px;
                            overflow-wrap: break-word;
                            word-wrap: break-word;
                            box-sizing: border-box;
                            overflow: hidden;
                        }
                        img {
                            max-width: 100%;
                            height: auto;
                            display: block;
                        }
                        * {
                            box-sizing: border-box;
                        }
                    </style>
                </head>
                <body>
                    $rawContent
                </body>
                </html>
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CoroutineScope(Dispatchers.IO).launch {
                        // Wait for content to be fully rendered
                        checkContentRendered(view, delay.toLong(), result, targetWidth)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    result.error("WEBVIEW_ERROR", "Failed to load content: ${error?.description}", null)
                }
            }

            // Set initial size to avoid clipping
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            webView.layout(0, 0, targetWidth, webView.measuredHeight)
            webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
        } else {
            result.notImplemented()
        }
    }

    private fun checkContentRendered(webView: WebView, delay: Long, result: MethodChannel.Result, targetWidth: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript(
                """
                (function() {
                    var images = document.getElementsByTagName('img');
                    var loaded = true;
                    for (var i = 0; i < images.length; i++) {
                        if (!images[i].complete) {
                            loaded = false;
                            break;
                        }
                    }
                    return {
                        width: document.body.scrollWidth,
                        height: document.body.scrollHeight,
                        fullyLoaded: loaded
                    };
                })();
                """
            ) { value ->
                try {
                    val json = JSONArray("[$value]").getJSONObject(0)
                    val contentWidth = json.getDouble("width").absoluteValue.toInt()
                    val contentHeight = json.getDouble("height").absoluteValue.toInt()
                    val fullyLoaded = json.getBoolean("fullyLoaded")

                    if (!fullyLoaded && delay < 5000) {
                        // Retry if content (e.g., images) is not fully loaded, up to 5 seconds
                        checkContentRendered(webView, delay + 500, result, targetWidth)
                        return@evaluateJavascript
                    }

                    if (contentWidth <= 0 || contentHeight <= 0) {
                        result.error("INVALID_SIZE", "Content size is invalid: $contentWidth x $contentHeight", null)
                        return@evaluateJavascript
                    }

                    // Ensure WebView is sized correctly
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, contentWidth, contentHeight)

                    val bitmap = webView.toBitmap(contentWidth.toDouble(), contentHeight.toDouble())
                    if (bitmap != null && !isBitmapWhite(bitmap)) {
                        result.success(bitmap.toByteArray())
                    } else {
                        result.error("BITMAP_NULL", "Failed to generate valid image", null)
                    }
                } catch (e: Exception) {
                    result.error("EVALUATION_ERROR", "JavaScript evaluation failed: ${e.message}", null)
                }
            }
        }, delay)
    }

    private fun isBitmapWhite(bitmap: Bitmap): Boolean {
        // Check a sample of pixels to determine if the bitmap is mostly white/transparent
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var nonWhiteCount = 0
        for (pixel in pixels) {
            if (pixel != 0 && pixel != -1) { // Not transparent or white
                nonWhiteCount++
            }
            if (nonWhiteCount > 10) return false // Early exit if enough non-white pixels
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun getDisplaySize(): Size {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            Size(bounds.width(), bounds.height())
        } else {
            val display = activity.windowManager.defaultDisplay
            Size(display.width, display.height)
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        webView = WebView(activity.applicationContext).apply {
            minimumHeight = 1
            minimumWidth = 1
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {}
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}

fun WebView.toBitmap(offsetWidth: Double, offsetHeight: Double): Bitmap? {
    if (offsetHeight > 0 && offsetWidth > 0) {
        val width = offsetWidth.absoluteValue.toInt()
        val height = offsetHeight.absoluteValue.toInt()
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }
    return null
}

fun Bitmap.toByteArray(): ByteArray {
    return ByteArrayOutputStream().use {
        compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
}