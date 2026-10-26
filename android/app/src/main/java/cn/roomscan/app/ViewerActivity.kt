package cn.roomscan.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.*
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

class ViewerActivity:Activity(){
    private lateinit var web:WebView
    private lateinit var model:File
    override fun onCreate(state:Bundle?){super.onCreate(state)
        val id=intent.getStringExtra("job")?:run{finish();return}
        if(!id.matches(Regex("[a-f0-9-]{36}"))){finish();return}
        model=File(filesDir,"models/$id")
        if(!File(model,"scene.ply").exists()||!File(model,"view.json").exists()){finish();return}
        window.statusBarColor=Color.rgb(16,23,33);window.navigationBarColor=Color.rgb(16,23,33);window.decorView.systemUiVisibility=0
        web=WebView(this);setContentView(web)
        web.setBackgroundColor(Color.rgb(16,23,33))
        web.settings.apply{javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false;mediaPlaybackRequiresUserGesture=true}
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(object{
            @JavascriptInterface fun closeViewer(){runOnUiThread{finish()}}
            @JavascriptInterface fun rotateLayout(){runOnUiThread{
                requestedOrientation=if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }}
            @JavascriptInterface fun debugEnabled():Boolean=BuildConfig.DEBUG
            @JavascriptInterface fun reportState(text:String){runCatching{
                val report=JSONObject(text).put("job",id).put("recorded_at",System.currentTimeMillis())
                File(filesDir,"viewer-report.json").writeText(report.toString(2))
            }}
        },"RoomScan")
        web.webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage):Boolean{
            if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.e("RoomScanViewer",message.message());return true}}
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                val uri=request.url
                if(uri.scheme!="https"||uri.host!="app.roomscan.local")return denied()
                val path=uri.path?:return denied()
                if(path.contains(".."))return denied()
                return try{
                    val type=when{path.endsWith(".js")->"text/javascript";path.endsWith(".html")->"text/html";path.endsWith(".json")->"application/json";else->"application/octet-stream"}
                    val input=when(path){"/model.ply"->File(model,"scene.ply").inputStream();"/view.json"->File(model,"view.json").inputStream()
                        else->assets.open("viewer/"+(if(path=="/")"index.html" else path.removePrefix("/")))}
                    WebResourceResponse(type,if(type=="application/octet-stream")null else "utf-8",200,"OK",mapOf(
                        "Cross-Origin-Opener-Policy" to "same-origin","Cross-Origin-Embedder-Policy" to "require-corp",
                        "Cross-Origin-Resource-Policy" to "same-origin","Cache-Control" to "no-store"),input)
                }catch(_:Exception){denied()}
            }
            private fun denied()=WebResourceResponse("text/plain","utf-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(byteArrayOf()))
        }
        web.loadUrl("https://app.roomscan.local/index.html")
    }
    override fun onPause(){if(::web.isInitialized)web.onPause();super.onPause()}
    override fun onResume(){super.onResume();if(::web.isInitialized)web.onResume();window.insetsController?.apply{
        systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsets.Type.systemBars())
    }}
    override fun onDestroy(){if(::web.isInitialized){web.removeJavascriptInterface("RoomScan");web.destroy()};super.onDestroy()}
}
