package cn.roomscan.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class HomeActivity:Activity() {
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var connectionTitle:TextView
    private lateinit var connectionDetail:TextView
    private lateinit var sceneList:LinearLayout
    private val io=Executors.newSingleThreadExecutor()
    private val servers=linkedMapOf<String,Pair<String,String>>()
    private var discovery:NsdManager.DiscoveryListener?=null
    private var nsd:NsdManager?=null
    private val preferences by lazy {getSharedPreferences("server",MODE_PRIVATE)}
    private var connected=false
    private var working=false
    private var selectedTab=0
    private var lastAutoAttempt=0L
    private val jobs=linkedMapOf<String,JSONObject>()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var visible=false
    private var lastModelAttempt=0L
    private val poll=Runnable{checkConnection()}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);Ui.screen(this)
        runCatching{val data=org.json.JSONArray(File(filesDir,"jobs-cache.json").readText());for(i in 0 until data.length()){val job=data.getJSONObject(i);jobs[job.getString("id")]=job}}
        buildHome();discover()
        if(BuildConfig.DEBUG)intent.getStringExtra("pair_payload")?.let{pair(it)}
    }
    override fun onResume(){super.onResume();visible=true;if(::sceneList.isInitialized)renderScenes();checkConnection()}
    override fun onPause(){visible=false;handler.removeCallbacks(poll);super.onPause()}

    private fun buildHome(){
        root=Ui.column(this).apply{setBackgroundColor(Ui.surface)};setContentView(root);Ui.insets(root)
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        content=Ui.column(this,22);scroll.addView(content)
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(ImageView(this).apply{setImageResource(cn.roomscan.app.R.drawable.roomscan_logo)},LinearLayout.LayoutParams(Ui.dp(this,36),Ui.dp(this,36)).apply{marginEnd=Ui.dp(this@HomeActivity,10)})
        header.addView(Ui.label(this,"留境",25f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Ui.label(this,"ROOMSCAN",10f,Ui.muted).apply{letterSpacing=.16f})
        content.addView(header);content.addView(Ui.space(this,26))
        content.addView(Ui.label(this,"留住空间的每一面",26f,bold=true))
        content.addView(Ui.space(this,8));content.addView(Ui.label(this,"从一段录制，开始一次空间记录。",14f,Ui.muted))
        content.addView(Ui.space(this,22))
        val hero=Ui.column(this,20).apply{
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFE3EDFF.toInt(),0xFFE9F6F1.toInt())).apply{cornerRadius=Ui.dp(this@HomeActivity,26).toFloat()}}
        val heroTop=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val heroCopy=Ui.column(this)
        heroCopy.addView(Ui.label(this,"开始新的扫描",21f,bold=true));heroCopy.addView(Ui.space(this,10))
        heroCopy.addView(Ui.label(this,"房间 · 物品 · 身边的环境",12f,Ui.muted))
        heroTop.addView(heroCopy,LinearLayout.LayoutParams(0,-2,1f))
        heroTop.addView(SpatialArtwork(this),LinearLayout.LayoutParams(Ui.dp(this,118),Ui.dp(this,125)))
        hero.addView(heroTop);hero.addView(Ui.space(this,8))
        hero.addView(Ui.button(this,"＋  开始扫描"){startCapture()},LinearLayout.LayoutParams(-1,Ui.dp(this,54)))
        content.addView(hero);content.addView(Ui.space(this,16))
        val serverCard=Ui.column(this,18).apply{background=Ui.rounded(Color.WHITE);setOnClickListener{showDevices()}}
        val serverRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val mark=Ui.label(this,"▣",26f,Ui.blue).apply{gravity=Gravity.CENTER}
        serverRow.addView(mark,LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)))
        val serverCopy=Ui.column(this)
        connectionTitle=Ui.label(this,"正在寻找电脑",15f,bold=true);serverCopy.addView(connectionTitle)
        serverCopy.addView(Ui.space(this,5));connectionDetail=Ui.label(this,"同一 Wi-Fi 下自动发现",12f,Ui.muted);serverCopy.addView(connectionDetail)
        serverRow.addView(serverCopy,LinearLayout.LayoutParams(0,-2,1f));serverRow.addView(Ui.label(this,"›",28f,Ui.muted))
        serverCard.addView(serverRow);content.addView(serverCard);content.addView(Ui.space(this,26))
        val recentHeader=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        recentHeader.addView(Ui.label(this,"我的空间",19f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        recentHeader.addView(Ui.label(this,"保存在本机",11f,Ui.muted))
        content.addView(recentHeader);content.addView(Ui.space(this,12))
        sceneList=Ui.column(this);content.addView(sceneList)
        content.addView(Ui.space(this,20))
        content.addView(Ui.label(this,"关于留境 · 开源许可",12f,Ui.muted).apply{
            setPadding(0,Ui.dp(this@HomeActivity,12),0,Ui.dp(this@HomeActivity,12))
            setOnClickListener{
                val text=TextView(this@HomeActivity).apply{
                    text=assets.open("licenses/NOTICE.txt").bufferedReader().use{it.readText()}
                    textSize=12f;setPadding(24,16,24,16)
                    autoLinkMask=android.text.util.Linkify.WEB_URLS
                    movementMethod=android.text.method.LinkMovementMethod.getInstance()
                }
                AlertDialog.Builder(this@HomeActivity).setTitle("留境 ${BuildConfig.VERSION_NAME}")
                    .setView(ScrollView(this@HomeActivity).apply{addView(text)})
                    .setPositiveButton("关闭",null).show()
            }
        })
        buildNavigation();renderScenes();updateConnection()
    }

    private fun buildNavigation(){
        val nav=LinearLayout(this).apply{setPadding(Ui.dp(this@HomeActivity,22),Ui.dp(this@HomeActivity,10),Ui.dp(this@HomeActivity,22),Ui.dp(this@HomeActivity,14));setBackgroundColor(Color.WHITE)}
        listOf("◈  空间","▣  设备").forEachIndexed{index,value ->
            nav.addView(Ui.label(this,value,14f,if(index==selectedTab)Ui.blue else Ui.muted,true).apply{
                gravity=Gravity.CENTER;setPadding(0,Ui.dp(this@HomeActivity,9),0,Ui.dp(this@HomeActivity,9))
                setOnClickListener{if(index==1)showDevices()}
            },LinearLayout.LayoutParams(0,-2,1f))}
        root.addView(nav)
    }

    private fun renderScenes(){
        sceneList.removeAllViews()
        val captures=File(filesDir,"captures").listFiles()?.filter{File(it,"capture.json").exists()}?.sortedByDescending{it.name}?:emptyList()
        if(captures.isEmpty()){
            val empty=Ui.column(this,22).apply{background=Ui.rounded(Color.WHITE)}
            empty.addView(Ui.label(this,"你的第一处空间，等你记录",15f,bold=true))
            empty.addView(Ui.space(this,8));empty.addView(Ui.label(this,"点击开始扫描，沿着目标缓慢移动。\n每次录制都会先安全保存在手机。",12f,Ui.muted))
            sceneList.addView(empty);return
        }
        captures.forEach{directory ->
            val metadata=runCatching{JSONObject(File(directory,"capture.json").readText())}.getOrDefault(JSONObject())
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(Ui.dp(this@HomeActivity,12),Ui.dp(this@HomeActivity,12),Ui.dp(this@HomeActivity,12),Ui.dp(this@HomeActivity,12));background=Ui.rounded(Color.WHITE);setOnClickListener{showCapture(directory)}}
            val thumbnail=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;background=Ui.rounded(0xFFE4ECFC.toInt(),14f);clipToOutline=true}
            directory.listFiles()?.firstOrNull{it.extension=="jpg"}?.let{file ->thumbnail.setImageBitmap(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=4}))}
            row.addView(thumbnail,LinearLayout.LayoutParams(Ui.dp(this,66),Ui.dp(this,66)))
            val copy=Ui.column(this).apply{setPadding(Ui.dp(this@HomeActivity,14),0,0,0)}
            val date=java.text.SimpleDateFormat("MM月dd日 HH:mm",java.util.Locale.CHINA).format(java.util.Date(directory.name.toLongOrNull()?:0))
            copy.addView(Ui.label(this,"空间记录 · $date",14f,bold=true));copy.addView(Ui.space(this,7))
            val count=metadata.optJSONArray("keyframes")?.length()?:0
            val transferred=runCatching{JSONObject(File(directory,"upload.json").readText()).has("job_id")}.getOrDefault(false)
            val job=jobFor(directory)
            val state=when{count==0->"建议补拍 · 画面信息不足"
                job?.optString("state")=="ready"->if(File(filesDir,"models/${job.getString("id")}/scene.ply").exists())"可探索 · 已离线保存" else "三维场景已生成"
                job?.optString("state")=="failed"->"处理未完成 · 可查看详情"
                job!=null->stageLabel(job.optString("stage"))
                transferred->"已同步 · 等待处理"
                else->"已保存 · 待同步"}
            copy.addView(Ui.label(this,"${metadata.optLong("duration_ms")/1000} 秒 · $count 个视角",11f,Ui.muted));copy.addView(Ui.space(this,5))
            copy.addView(Ui.label(this,state,11f,Ui.blue))
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));row.addView(Ui.label(this,"›",22f,Ui.muted))
            sceneList.addView(row);sceneList.addView(Ui.space(this,10))
        }
    }

    private fun showCapture(directory:File){
        val job=jobFor(directory)
        val actions=if(job?.optString("state")=="ready")arrayOf("探索三维空间","回看录制视频","提高清晰度") else arrayOf("回看录制视频",if(job==null)"同步到电脑" else "查看处理进度")
        AlertDialog.Builder(this).setTitle("空间记录").setItems(actions){_,index ->
            when(actions[index]){
                "探索三维空间"->openModel(job!!)
                "回看录制视频"->startActivity(Intent(this,PlaybackActivity::class.java).putExtra("capture",directory.name))
                "同步到电脑"->upload(directory)
                "提高清晰度"->upgrade(job!!)
                else->AlertDialog.Builder(this).setTitle(stageLabel(job!!.optString("stage")))
                    .setMessage(if(job.optString("state")=="failed")"部分视角未能组成稳定空间。可以保留视频，并补拍更多重叠视角后重新扫描。" else "电脑正在处理这次扫描。完成后模型会缓存在手机，之后可以离线探索。")
                    .setPositiveButton("知道了",null).show()
            }
        }.setNegativeButton("关闭",null).show()
    }
    private fun jobFor(directory:File):JSONObject?=runCatching{
        val state=JSONObject(File(directory,"upload.json").readText())
        val candidates=jobs.values.filter{it.optString("project_id")==state.getString("project_id")}
        candidates.firstOrNull{it.optString("state")=="ready"}?:candidates.firstOrNull()?:jobs[state.optString("job_id")]
    }.getOrNull()
    private fun upgrade(job:JSONObject){
        if(!connected){toast("电脑在线时可以提高清晰度");return}
        io.execute{try{
            val client=PinnedClient(preferences.getString("url","")!!,preferences.getString("pin","")!!,preferences.getString("token","")!!)
            client.json("POST","/v1/projects/${job.getString("project_id")}/jobs",JSONObject().put("quality","high"))
            runOnUiThread{toast("电脑将提升场景清晰度，当前版本仍可浏览");checkConnection()}
        }catch(_:Exception){runOnUiThread{toast("暂时无法开始，请稍后重试")}}}
    }
    private fun stageLabel(stage:String)=when(stage){"features"->"正在整理画面";"matching"->"正在关联视角";"mapping"->"正在恢复空间结构";"undistorting"->"正在准备场景";"training"->"正在生成三维场景";"ready"->"三维场景已生成";"failed"->"这次扫描需要补充视角";else->"已同步 · 等待处理"}

    private fun startCapture(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.CAMERA),20);return}
        // Camera2 capture is always available as the baseline; ARCore is an optional capability.
        startActivity(Intent(this,VideoCaptureActivity::class.java))
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED){if(code==20)startCapture() else if(code==21)scanPair()}
    }
    private fun scanPair(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.CAMERA),21);return}
        startActivityForResult(Intent(this,PairScanActivity::class.java),30)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==30&&resultCode==RESULT_OK)data?.getStringExtra("payload")?.let{pair(it)}}

    private fun showDevices(){
        val panel=Ui.column(this,22)
        panel.addView(Ui.label(this,if(connected)"电脑已连接" else if(servers.isNotEmpty())"已发现 ${servers.size} 台电脑" else "寻找附近的电脑",21f,bold=true))
        panel.addView(Ui.space(this,12))
        panel.addView(Ui.label(this,if(connected)"连接已保存。以后会自动发现并重连。" else "在电脑上打开留境服务，首次连接时\n扫描电脑页面上的配对二维码。",14f,Ui.muted))
        if(servers.isNotEmpty()){panel.addView(Ui.space(this,18));servers.values.forEach{panel.addView(Ui.label(this,"▣  ${it.first}",15f))}}
        panel.addView(Ui.space(this,22))
        val dialog=AlertDialog.Builder(this).setView(panel).setNegativeButton("完成",null).create()
        panel.addView(Ui.button(this,if(connected)"连接另一台电脑" else "扫描电脑配对码"){dialog.dismiss();scanPair()})
        dialog.show()
    }

    private fun pair(payload:String){
        if(working)return
        working=true
        io.execute{
            try {
                val qr=JSONObject(payload)
                require(qr.getString("type")=="roomscan-pair"&&qr.getInt("v")==1){"这不是留境配对码"}
                val pin=qr.getString("pin");require(pin.matches(Regex("[a-f0-9]{64}"))){"配对码无效"}
                val url=servers[qr.getString("server_id")]?.second?:qr.getString("endpoint")
                val client=PinnedClient(url,pin)
                val health=client.json("GET","/v1/health")
                require(health.getString("server_id")==qr.getString("server_id")){"电脑身份不匹配"}
                val result=client.json("POST","/v1/pair",JSONObject().put("code",qr.getString("code")).put("name",android.os.Build.MODEL))
                preferences.edit().putString("url",url).putString("pin",pin).putString("token",result.getString("token"))
                    .putString("server_id",result.getString("server_id")).apply()
                connected=true;runOnUiThread{updateConnection();toast("连接成功，以后会自动重连");syncPending()}
            }catch(e:Exception){runOnUiThread{AlertDialog.Builder(this).setTitle("暂时无法连接").setMessage(friendly(e)).setPositiveButton("知道了",null).show()}}
            finally{working=false}
        }
    }

    private fun checkConnection(){
        val token=preferences.getString("token",null)?:return
        io.execute{
            var incoming:org.json.JSONArray?=null
            connected=runCatching{val client=PinnedClient(preferences.getString("url","")!!,preferences.getString("pin","")!!,token)
                val health=client.json("GET","/v1/health")
                require(health.getString("server_id")==preferences.getString("server_id",""))
                incoming=client.json("GET","/v1/sync").getJSONArray("jobs")
                File(filesDir,"jobs-cache.json").writeText(incoming.toString());true}.getOrDefault(false)
            runOnUiThread{incoming?.let{data->jobs.clear();for(i in 0 until data.length()){val job=data.getJSONObject(i);jobs[job.getString("id")]=job}}
                updateConnection();renderScenes();syncPending();cacheNextModel();schedulePoll()}
        }
    }
    private fun schedulePoll(){handler.removeCallbacks(poll);if(visible&&jobs.values.any{it.optString("state")in listOf("running","queued","waiting_engine")})handler.postDelayed(poll,5000)}
    private fun cacheNextModel(){
        if(working||!connected||android.os.SystemClock.elapsedRealtime()-lastModelAttempt<15000)return
        val job=jobs.values.firstOrNull{it.optString("state")=="ready"&&!File(filesDir,"models/${it.getString("id")}/scene.ply").exists()}?:return
        lastModelAttempt=android.os.SystemClock.elapsedRealtime();downloadModel(job,false)
    }
    private fun openModel(job:JSONObject){
        val id=job.getString("id")
        if(File(filesDir,"models/$id/scene.ply").exists()&&File(filesDir,"models/$id/view.json").exists()){
            val viewFile=File(filesDir,"models/$id/view.json")
            viewFile.writeText(calibratedView(job,JSONObject(viewFile.readText())).toString())
            startActivity(Intent(this,ViewerActivity::class.java).putExtra("job",id))
        }else downloadModel(job,true)
    }
    private fun calibratedView(job:JSONObject,original:JSONObject):JSONObject{
        val view=JSONObject(original.toString())
        val captureDirectory=File(filesDir,"captures").listFiles()?.firstOrNull{dir->runCatching{
            JSONObject(File(dir,"upload.json").readText()).optString("project_id")==job.getString("project_id")}.getOrDefault(false)}?:return view
        return runCatching{
            val capture=JSONObject(File(captureDirectory,"capture.json").readText())
            if(capture.optString("capture_mode")=="camera2_sfm"){
                view.put("display_rotation_degrees",capture.optInt("orientation_degrees",capture.optInt("sensor_orientation")))
                val frames=capture.optJSONArray("keyframes")
                val reference=(0 until (frames?.length()?:0)).map{frames!!.getJSONObject(it)}
                    .firstOrNull{it.optString("file")==view.optString("reference_image")}?:frames?.optJSONObject(0)
                reference?.optJSONArray("gravity_device")?.let{view.put("gravity_device",it)}
                if(!view.has("gravity_device")&&reference!=null){
                    val time=reference.getLong("image_timestamp_ns");val sum=DoubleArray(3);var count=0
                    val imuFile=File(captureDirectory,"imu.jsonl")
                    if(imuFile.exists())imuFile.forEachLine{line->val sample=JSONObject(line)
                        if(sample.optInt("type")==android.hardware.Sensor.TYPE_ACCELEROMETER&&kotlin.math.abs(sample.getLong("timestamp_ns")-time)<250_000_000){
                            val values=sample.getJSONArray("values");for(i in 0..2)sum[i]+=values.getDouble(i);count++}}
                    if(count>=3)view.put("gravity_device",org.json.JSONArray(sum.map{it/count}))
                }
            };view
        }.getOrDefault(view)
    }
    private fun downloadModel(job:JSONObject,openAfter:Boolean){
        if(working){toast("正在同步，请稍候");return}
        working=true;connectionDetail.text="正在把三维场景保存到手机…"
        io.execute{
            try{
                val id=job.getString("id");require(id.matches(Regex("[a-f0-9-]{36}")))
                val model=job.getJSONObject("model");require(model.getLong("bytes")<=128L*1024*1024)
                val directory=File(filesDir,"models/$id").apply{mkdirs()}
                val client=PinnedClient(preferences.getString("url","")!!,preferences.getString("pin","")!!,preferences.getString("token","")!!)
                client.download(model.getString("url"),File(directory,"scene.ply"),model.getString("sha256"))
                File(directory,"view.json").writeText(calibratedView(job,model.getJSONObject("view")).toString())
                runOnUiThread{renderScenes();updateConnection();if(openAfter)startActivity(Intent(this,ViewerActivity::class.java).putExtra("job",id))}
            }catch(e:Exception){runOnUiThread{connectionDetail.text="三维场景下载暂未完成，可稍后重试"}}
            finally{working=false}
        }
    }
    private fun updateConnection(){if(!::connectionTitle.isInitialized)return
        connectionTitle.text=if(connected)"电脑已连接" else if(servers.isNotEmpty())"发现附近的电脑" else "正在寻找电脑"
        connectionDetail.text=if(connected)"录制保存在手机，精选数据同步到电脑" else if(servers.isNotEmpty())"首次扫码连接，之后自动重连" else "电脑未在线也可以先录制"}

    private fun discover(){
        val manager=getSystemService(NsdManager::class.java);nsd=manager
        val listener=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){}
            override fun onStartDiscoveryFailed(type:String,error:Int){}
            override fun onStopDiscoveryFailed(type:String,error:Int){}
            override fun onServiceLost(info:NsdServiceInfo){runOnUiThread{servers.entries.removeAll{it.value.first==info.serviceName};updateConnection()}}
            override fun onServiceFound(info:NsdServiceInfo){
                if(info.serviceType!="_gsroom._tcp.")return
                @Suppress("DEPRECATION")manager.resolveService(info,object:NsdManager.ResolveListener{
                    override fun onResolveFailed(info:NsdServiceInfo,error:Int){}
                    override fun onServiceResolved(info:NsdServiceInfo){
                        val host=info.host?.hostAddress?:return
                        val id=info.attributes["id"]?.toString(Charsets.UTF_8)?:return
                        val url="https://${if(host.contains(':'))"[$host]" else host}:${info.port}"
                        runOnUiThread{servers[id]=Pair(info.serviceName,url)
                            if(preferences.getString("server_id",null)==id){preferences.edit().putString("url",url).apply();checkConnection()};updateConnection()}
                        File(filesDir,"discovery.json").writeText(JSONObject().put("server_id",id).put("host",host).put("port",info.port).toString())
                    }
                })
            }
        };discovery=listener
        try{manager.discoverServices("_gsroom._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)}catch(_:Exception){}
    }

    private fun syncPending(){
        if(!connected||working||android.os.SystemClock.elapsedRealtime()-lastAutoAttempt<30000)return
        val next=File(filesDir,"captures").listFiles()?.filter{File(it,"capture.json").exists()}
            ?.sortedBy{it.name}?.firstOrNull{dir->
                !runCatching{JSONObject(File(dir,"upload.json").readText()).has("job_id")}.getOrDefault(false)&&
                    (dir.listFiles()?.count{it.extension=="jpg"}?:0)>0
            }?:return
        lastAutoAttempt=android.os.SystemClock.elapsedRealtime();upload(next,true)
    }
    private fun upload(directory:File,automatic:Boolean=false){
        if(working){toast("正在同步，请稍候");return}
        val token=preferences.getString("token",null)?:run{showDevices();return}
        working=true
        val progress=Ui.label(this,"正在准备这次扫描…",14f,Ui.muted).apply{setPadding(40,30,40,30)}
        val dialog=AlertDialog.Builder(this).setTitle("同步空间记录").setView(progress).setCancelable(false).create()
        if(!automatic)dialog.show()else connectionDetail.text="正在自动同步精选视角…"
        io.execute{
            try{
                val client=PinnedClient(preferences.getString("url","")!!,preferences.getString("pin","")!!,token)
                CaptureUpload(client).upload(directory){message->runOnUiThread{progress.text=message}}
                runOnUiThread{dialog.dismiss();renderScenes();updateConnection();checkConnection();if(!automatic)AlertDialog.Builder(this).setTitle("扫描已送达电脑")
                    .setMessage("电脑将自动生成三维场景。原始录制继续保存在手机。")
                    .setPositiveButton("完成",null).show()}
            }catch(e:Exception){runOnUiThread{dialog.dismiss();updateConnection();if(!automatic)AlertDialog.Builder(this).setTitle("同步暂未完成").setMessage(friendly(e)).setPositiveButton("稍后重试",null).show()
                else connectionDetail.text="记录已保存在手机，连接恢复后重试"}}
            finally{working=false}
        }
    }
    private fun friendly(e:Exception):String=when {
        e.toString().contains("403")->"配对码已过期，请在电脑上刷新二维码后重试。"
        e.toString().contains("identity",true)->"电脑身份发生变化，请重新扫描电脑上的配对码。"
        e.toString().contains("timeout",true)||e is java.net.ConnectException->"请确认电脑服务已打开，手机和电脑连接同一 Wi-Fi。"
        else->"请确认电脑在线后重试。记录已经保存在手机。"
    }
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}
    override fun onDestroy(){discovery?.let{try{nsd?.stopServiceDiscovery(it)}catch(_:Exception){}};io.shutdown();super.onDestroy()}
}
