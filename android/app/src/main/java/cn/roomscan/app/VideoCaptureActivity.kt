package cn.roomscan.app

import android.app.Activity
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoCaptureActivity:Activity(),SensorEventListener {
    private lateinit var preview:TextureView
    private lateinit var primary:Button
    private lateinit var status:TextView
    private lateinit var timer:TextView
    private lateinit var lensRow:LinearLayout
    private lateinit var recordLabel:TextView
    private lateinit var captureHeader:View
    private lateinit var captureControls:View
    private var lenses=emptyList<CaptureLens>()
    private var selectedLens:CaptureLens?=null
    private var previewHost:FrameLayout?=null
    private val cameraThread=HandlerThread("capture-camera")
    private lateinit var cameraHandler:Handler
    private val disk=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val main=Handler(Looper.getMainLooper())
    private var camera:CameraDevice?=null
    private var cameraSession:CameraCaptureSession?=null
    private var reader:ImageReader?=null
    private var recorder:MediaRecorder?=null
    private var size=Size(1920,1080)
    private var cameraId="0"
    private var sensorOrientation=90
    private var previewSurface:Surface?=null
    private var directory:File?=null
    private var cameraInfo=JSONObject()
    private var imu:java.io.BufferedWriter?=null
    private var frameInfo:java.io.BufferedWriter?=null
    @Volatile private var recording=false
    @Volatile private var acceptingFrames=false
    @Volatile private var latestResult=JSONObject()
    @Volatile private var lastImageTime=0L
    @Volatile private var saved=0
    private var started=0L
    private var opened=false
    private var transition=false
    private var sampleCount=0
    private var diagnosticTime=0L
    @Volatile private var qualityHint=""
    private var rejected=0
    @Volatile private var latestGravity:FloatArray?=null
    private val sensors by lazy{getSystemService(SensorManager::class.java)}
    private val tick=object:Runnable{override fun run(){if(recording){val seconds=(SystemClock.elapsedRealtime()-started)/1000
        timer.text="●  %02d:%02d".format(seconds/60,seconds%60);status.text=qualityHint.ifEmpty{"已收集 $saved 个视角 · 缓慢移动，保持画面稳定"}
        main.postDelayed(this,500)}}}

    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Ui.screen(this)
        val root=FrameLayout(this).apply{setBackgroundColor(Ui.surface)};setContentView(root);Ui.insets(root)
        previewHost=FrameLayout(this);root.addView(previewHost,FrameLayout.LayoutParams(-1,-1))
        preview=TextureView(this);previewHost!!.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(Ui.dp(this@VideoCaptureActivity,18),0,Ui.dp(this@VideoCaptureActivity,18),0)}
        header.addView(Ui.label(this,"‹",30f).apply{gravity=Gravity.CENTER;background=Ui.rounded(Color.WHITE,16f);contentDescription="返回";setOnClickListener{requestClose()}},LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)))
        val title=Ui.column(this).apply{setPadding(Ui.dp(this@VideoCaptureActivity,14),0,0,0)}
        title.addView(Ui.label(this,"扫描空间",19f,Ui.ink,true));title.addView(Ui.space(this,5));title.addView(Ui.label(this,"留住空间的每一面",10f,Ui.muted))
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        timer=Ui.label(this,"准备就绪",11f,0xFF327664.toInt(),true).apply{gravity=Gravity.CENTER;background=Ui.rounded(0xFFE2F3ED.toInt(),18f);setPadding(Ui.dp(this@VideoCaptureActivity,12),0,Ui.dp(this@VideoCaptureActivity,12),0)}
        header.addView(timer,LinearLayout.LayoutParams(-2,Ui.dp(this,32)))
        captureHeader=header;root.addView(header,FrameLayout.LayoutParams(-1,Ui.dp(this,70),Gravity.TOP))
        val bottom=Ui.column(this).apply{setPadding(Ui.dp(this@VideoCaptureActivity,18),Ui.dp(this@VideoCaptureActivity,10),Ui.dp(this@VideoCaptureActivity,18),Ui.dp(this@VideoCaptureActivity,10))}
        status=Ui.label(this,"正在打开相机…",11f,Ui.muted).apply{gravity=Gravity.CENTER;isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END};bottom.addView(status,LinearLayout.LayoutParams(-1,Ui.dp(this,18)))
        bottom.addView(Ui.space(this,10))
        lensRow=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL};bottom.addView(lensRow,LinearLayout.LayoutParams(-1,Ui.dp(this,42)))
        bottom.addView(Ui.space(this,12))
        val shutterRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        fun sideLabel(value:String)=Ui.label(this,value,10f,Ui.muted).apply{gravity=Gravity.CENTER;setLineSpacing(Ui.dp(this@VideoCaptureActivity,5).toFloat(),1f)}
        shutterRow.addView(sideLabel("完整画幅\n保留视场"),LinearLayout.LayoutParams(0,-2,1f))
        primary=CaptureShutter(this).apply{text="开始录制";isEnabled=false;setOnClickListener{if(!transition){if(recording)stopRecording()else startRecording()}}}
        shutterRow.addView(primary,LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)))
        shutterRow.addView(sideLabel("本机保存\n自动同步"),LinearLayout.LayoutParams(0,-2,1f));bottom.addView(shutterRow)
        bottom.addView(Ui.space(this,5));recordLabel=Ui.label(this,"点击开始录制",11f,Ui.ink,true).apply{gravity=Gravity.CENTER};bottom.addView(recordLabel,LinearLayout.LayoutParams(-1,Ui.dp(this,18)))
        captureControls=bottom;root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->
            transformPreview()
        }
        cameraThread.start();cameraHandler=Handler(cameraThread.looper)
        preview.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture:SurfaceTexture,w:Int,h:Int){openCamera()}
            override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,w:Int,h:Int){transformPreview()}
            override fun onSurfaceTextureDestroyed(texture:SurfaceTexture)=true
            override fun onSurfaceTextureUpdated(texture:SurfaceTexture){}
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera(){
        if(opened||isFinishing)return
        opened=true
        try{
            val manager=getSystemService(CameraManager::class.java)
            if(lenses.isEmpty()){
                lenses=CaptureLenses.discover(manager)
                File(filesDir,"camera-lenses.json").writeText(CaptureLenses.report(lenses).toString(2))
            }
            val lens=selectedLens?:lenses.minByOrNull{kotlin.math.abs(it.equivalent-24)}?:error("No supported rear camera")
            selectedLens=lens;cameraId=lens.deviceId
            val info=lens.info
            sensorOrientation=info.get(CameraCharacteristics.SENSOR_ORIENTATION)?:90
            size=lens.size
            main.post{renderLenses();transformPreview()}
            cameraInfo=JSONObject().put("camera_id",cameraId).put("image_width",size.width).put("image_height",size.height)
                .put("sensor_orientation",sensorOrientation).put("timestamp_source",info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                .put("lens_intrinsic_calibration",JSONArray(info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList()?:emptyList<Float>()))
                .put("physical_sensor_mm",info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let{JSONArray(listOf(it.width,it.height))})
                .put("lens",lens.metadata()).put("active_array",info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let{JSONArray(listOf(it.left,it.top,it.right,it.bottom))})
                .put("framing","sensor_aspect_fit").put("video_stabilization",false)
            reader=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,3).apply{setOnImageAvailableListener({source->onImage(source)},cameraHandler)}
            manager.openCamera(cameraId,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice){camera=device;createSession(false)}
                override fun onDisconnected(device:CameraDevice){device.close();camera=null;showError("相机连接中断，请重新打开扫描")}
                override fun onError(device:CameraDevice,error:Int){device.close();camera=null;showError("相机暂不可用，请关闭其他相机应用后重试")}
            },cameraHandler)
        }catch(e:Exception){showError("无法打开相机，请返回后重试");writeError(e)}
    }

    private fun createSession(withRecorder:Boolean){
        val device=camera?:return
        cameraSession?.close();cameraSession=null
        val texture=preview.surfaceTexture?:return
        texture.setDefaultBufferSize(size.width,size.height)
        previewSurface?.release();previewSurface=Surface(texture)
        val targets=mutableListOf(previewSurface!!,reader!!.surface)
        if(withRecorder)targets.add(recorder!!.surface)
        val request=device.createCaptureRequest(if(withRecorder)CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply{
            targets.forEach{addTarget(it)}
            set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(30,30))
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if(selectedLens?.physicalId==null)set(CaptureRequest.CONTROL_ZOOM_RATIO,1f)
            selectedLens?.info?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let{set(CaptureRequest.SCALER_CROP_REGION,it)}
        }
        val callback=object:CameraCaptureSession.StateCallback(){
            override fun onConfigured(session:CameraCaptureSession){
                if(camera==null)return
                cameraSession=session
                try{
                    session.setRepeatingRequest(request.build(),object:CameraCaptureSession.CaptureCallback(){
                        override fun onCaptureCompleted(session:CameraCaptureSession,request:CaptureRequest,result:TotalCaptureResult){
                            val physical=selectedLens?.physicalId?.let{result.physicalCameraResults[it]}?:result
                            val crop=physical.get(CaptureResult.SCALER_CROP_REGION)
                            latestResult=JSONObject().put("timestamp_ns",physical.get(CaptureResult.SENSOR_TIMESTAMP)?:result.get(CaptureResult.SENSOR_TIMESTAMP))
                                .put("exposure_ns",physical.get(CaptureResult.SENSOR_EXPOSURE_TIME)).put("iso",physical.get(CaptureResult.SENSOR_SENSITIVITY))
                                .put("focal_length_mm",physical.get(CaptureResult.LENS_FOCAL_LENGTH))
                                .put("crop_region",crop?.let{JSONArray(listOf(it.left,it.top,it.right,it.bottom))})
                                .put("physical_camera_id",selectedLens?.physicalId).put("zoom_ratio",result.get(CaptureResult.CONTROL_ZOOM_RATIO))
                            if(BuildConfig.DEBUG&&SystemClock.elapsedRealtime()-diagnosticTime>1000){diagnosticTime=SystemClock.elapsedRealtime();File(filesDir,"camera-live.json").writeText(JSONObject(cameraInfo.toString()).put("result",latestResult).toString())}
                        }
                    },cameraHandler)
                    if(withRecorder){recorder!!.start();started=SystemClock.elapsedRealtime();recording=true;acceptingFrames=true
                        main.post{startSensors();primary.text="结束并保存";recordLabel.text="点击结束并保存";primary.isEnabled=true;transition=false;tick.run()}}
                    else main.post{transformPreview();primary.isEnabled=true;transition=false;renderLenses();status.text="围绕物品或沿房间缓慢走动"}
                }catch(e:Exception){showError("无法开始采集，请返回后重试");writeError(e)}
            }
            override fun onConfigureFailed(session:CameraCaptureSession){showError("当前画面配置不可用，请返回后重试")}
        }
        val outputs=targets.map{surface->OutputConfiguration(surface).apply{selectedLens?.physicalId?.let{setPhysicalCameraId(it)}}}
        device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,java.util.concurrent.Executor{cameraHandler.post(it)},callback))
    }

    private fun startRecording(){
        transition=true;primary.isEnabled=false;renderLenses()
        directory=File(filesDir,"captures/${System.currentTimeMillis()}").apply{mkdirs()}
        saved=0;sampleCount=0;lastImageTime=0;qualityHint="";rejected=0
        imu=File(directory,"imu.jsonl").bufferedWriter();frameInfo=File(directory,"camera-frames.jsonl").bufferedWriter()
        cameraHandler.post{
            try{
                recorder=MediaRecorder(this).apply{
                    setVideoSource(MediaRecorder.VideoSource.SURFACE);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(File(directory,"recording.mp4").path);setVideoEncodingBitRate((size.width.toLong()*size.height*6).coerceIn(8_000_000,30_000_000).toInt())
                    setVideoFrameRate(30);setVideoSize(size.width,size.height);setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setOrientationHint(sensorOrientation);prepare()
                }
                createSession(true)
            }catch(e:Exception){showError("录制未能开始，请返回后重试");writeError(e)}
        }
    }

    private fun onImage(source:ImageReader){
        val image=try{source.acquireLatestImage()}catch(_:Exception){null}?:return
        if(!acceptingFrames||image.timestamp-lastImageTime<500_000_000||saved>=600||!busy.compareAndSet(false,true)){image.close();return}
        lastImageTime=image.timestamp
        val metadata=JSONObject(latestResult.toString()).put("image_timestamp_ns",image.timestamp)
            .put("width",image.width).put("height",image.height).put("tracking","UNAVAILABLE")
        latestGravity?.let{metadata.put("gravity_device",JSONArray(it.toList()))}
        val target=directory!!
        disk.execute{
            try{
                val bytes=toNv21(image)
                var sum=0L;var luminance=0L;var n=0
                for(y in 4 until image.height-4 step 8)for(x in 4 until image.width-4 step 8){val i=y*image.width+x
                    sum+=kotlin.math.abs((bytes[i].toInt() and 255)-(bytes[i+2].toInt() and 255));luminance+=(bytes[i].toInt() and 255);n++}
                val score=sum.toDouble()/n.coerceAtLeast(1)
                val brightness=luminance.toDouble()/n.coerceAtLeast(1)
                qualityHint=when{brightness<25->"光线偏暗，请对准光线充足的区域";score<=2.0->"画面细节较少，请对准有纹理的物品";else->""}
                if(score>2.0){
                    val name="frame_${image.timestamp}"
                    val temporary=File(target,"$name.jpg.part")
                    temporary.outputStream().use{output->check(YuvImage(bytes,ImageFormat.NV21,image.width,image.height,null)
                        .compressToJpeg(Rect(0,0,image.width,image.height),90,output))}
                    check(temporary.renameTo(File(target,"$name.jpg")))
                    metadata.put("file","$name.jpg").put("sharpness",score).put("mean_luma",brightness)
                    File(target,"$name.json").writeText(metadata.toString());saved++
                }else rejected++
            }catch(e:Exception){File(target,"image-error.txt").writeText(e.toString())}
            finally{image.close();busy.set(false)}
        }
        if(acceptingFrames){frameInfo?.apply{write(metadata.toString());newLine();flush()}}
    }

    private fun stopRecording(closeAfter:Boolean=false){
        if(!recording||transition)return
        transition=true;primary.isEnabled=false;acceptingFrames=false;recording=false
        main.removeCallbacks(tick);sensors.unregisterListener(this);imu?.close();imu=null
        val duration=SystemClock.elapsedRealtime()-started;val target=directory!!
        primary.text="正在保存…"
        recordLabel.text="正在安全保存…"
        cameraHandler.post{
            var validVideo=true
            try{cameraSession?.stopRepeating();cameraSession?.abortCaptures();recorder?.stop()}
            catch(e:Exception){validVideo=false;writeError(e)}
            finally{recorder?.release();recorder=null;frameInfo?.close();frameInfo=null}
            val complete=validVideo
            disk.execute{
                val frames=JSONArray()
                target.listFiles()?.filter{it.name.startsWith("frame_")&&it.extension=="json"}?.sortedBy{it.name}
                    ?.forEach{frames.put(JSONObject(it.readText()))}
                val capture=JSONObject(cameraInfo.toString()).put("schema_version",1).put("capture_mode","camera2_sfm")
                    .put("pose_source","none").put("ar_available",false).put("duration_ms",duration)
                    .put("keyframes",frames).put("video","recording.mp4").put("video_valid",complete)
                    .put("imu_samples",sampleCount).put("depth_recorded",false).put("orientation_degrees",sensorOrientation)
                    .put("quality_rejected_frames",rejected)
                val pending=File(target,"capture.json.part");pending.writeText(capture.toString(2));check(pending.renameTo(File(target,"capture.json")))
                main.post{if(closeAfter||isFinishing){if(!isFinishing)finish()}else{
                    timer.text="已安全保存";status.text="${duration/1000} 秒 · ${frames.length()} 个视角，返回后可同步到电脑"
                    primary.text="完成";recordLabel.text="已保存 · 点击完成";primary.isEnabled=true;primary.setOnClickListener{finish()};transition=false
                    cameraHandler.post{cameraSession?.close();cameraSession=null;camera?.close();camera=null}
                }}
            }
        }
    }
    private fun startSensors(){listOf(Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GRAVITY).forEach{type->
        sensors.getDefaultSensor(type)?.let{sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}}}
    override fun onSensorChanged(event:SensorEvent){if(!recording)return
        if(event.sensor.type==Sensor.TYPE_GRAVITY)latestGravity=event.values.copyOf(3)
        imu?.apply{write(JSONObject().put("timestamp_ns",event.timestamp).put("type",event.sensor.type).put("values",JSONArray(event.values.toList())).toString());newLine()};sampleCount++}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    private fun renderLenses(){
        lensRow.removeAllViews()
        lenses.forEach{lens->
            val button=Ui.button(this,lens.label){switchLens(lens)}.apply{
                text=lens.label.replace(" · ","\n");textSize=10f;isSingleLine=false;maxLines=2;includeFontPadding=false;setPadding(0,0,0,0)
                minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;isEnabled=!recording&&!transition
                setTextColor(if(lens==selectedLens)Color.WHITE else Ui.muted)
                background=Ui.rounded(if(lens==selectedLens)Ui.blue else 0xFFE9EEF6.toInt(),16f)
            }
            lensRow.addView(button,LinearLayout.LayoutParams(Ui.dp(this,86),Ui.dp(this,42)).apply{setMargins(Ui.dp(this@VideoCaptureActivity,4),0,Ui.dp(this@VideoCaptureActivity,4),0)})
        }
    }
    private fun switchLens(lens:CaptureLens){
        if(recording||transition||lens==selectedLens)return
        transition=true;primary.isEnabled=false;status.text="正在切换${lens.label}…";renderLenses()
        cameraHandler.post{
            cameraSession?.close();cameraSession=null;camera?.close();camera=null;reader?.close();reader=null
            selectedLens=lens;latestResult=JSONObject();opened=false;openCamera()
        }
    }
    private fun transformPreview(){
        val host=previewHost?:return
        if(host.width==0||host.height==0)return
        val sensorRotated=sensorOrientation==90||sensorOrientation==270
        val bufferWidth=if(sensorRotated)size.height.toFloat() else size.width.toFloat()
        val bufferHeight=if(sensorRotated)size.width.toFloat() else size.height.toFloat()
        val fit=host.width/bufferWidth
        val targetWidth=(bufferWidth*fit).toInt();val targetHeight=(bufferHeight*fit).toInt()
        val top=captureHeader.height;val bottom=captureControls.height
        val offset=((host.height-targetHeight+top-bottom)/2).coerceAtLeast(0)
        val layout=preview.layoutParams as FrameLayout.LayoutParams
        if(layout.width!=targetWidth||layout.height!=targetHeight||layout.topMargin!=offset){
            preview.layoutParams=FrameLayout.LayoutParams(targetWidth,targetHeight,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=offset}
            preview.post{transformPreview()};return
        }
        val viewWidth=preview.width.toFloat();val viewHeight=preview.height.toFloat()
        if(viewWidth==0f||viewHeight==0f)return
        val matrix=Matrix();val rotation=windowManager.defaultDisplay.rotation
        val buffer=RectF(0f,0f,if(sensorRotated)size.height.toFloat() else size.width.toFloat(),if(sensorRotated)size.width.toFloat() else size.height.toFloat())
        val view=RectF(0f,0f,viewWidth,viewHeight)
        buffer.offset(view.centerX()-buffer.centerX(),view.centerY()-buffer.centerY())
        matrix.setRectToRect(view,buffer,Matrix.ScaleToFit.FILL)
        val scale=minOf(viewHeight/buffer.height(),viewWidth/buffer.width());matrix.postScale(scale,scale,view.centerX(),view.centerY())
        if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270)matrix.postRotate(90f*(rotation-2),view.centerX(),view.centerY())
        preview.setTransform(matrix)
    }
    private fun showError(value:String){main.post{status.text=value;primary.isEnabled=false;transition=false;renderLenses()}}
    private fun requestClose(){if(recording)stopRecording(true)else if(!transition)finish()}
    @Deprecated("Platform back callback")
    override fun onBackPressed(){requestClose()}
    private fun writeError(e:Exception){File(filesDir,"camera2-error.txt").writeText(e.stackTraceToString())}
    override fun onPause(){if(recording)stopRecording(true);super.onPause()}
    override fun onDestroy(){
        sensors.unregisterListener(this);main.removeCallbacks(tick)
        cameraHandler.post{cameraSession?.close();camera?.close();reader?.close();previewSurface?.release();cameraThread.quitSafely()}
        disk.shutdown();super.onDestroy()
    }
}

internal fun toNv21(image:Image):ByteArray {
    val width=image.width;val height=image.height;val data=ByteArray(width*height*3/2)
    image.planes.forEachIndexed{index,plane->val buffer=plane.buffer;val base=buffer.position()
        val w=if(index==0)width else width/2;val h=if(index==0)height else height/2
        for(y in 0 until h)for(x in 0 until w){val target=if(index==0)y*width+x else width*height+y*width+x*2+if(index==1)1 else 0
            data[target]=buffer.get(base+y*plane.rowStride+x*plane.pixelStride)}}
    return data
}
