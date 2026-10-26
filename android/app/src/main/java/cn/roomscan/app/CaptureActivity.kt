package cn.roomscan.app

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CaptureActivity:Activity(),GLSurfaceView.Renderer {
    private lateinit var surface:GLSurfaceView
    private lateinit var status:TextView
    private lateinit var button:Button
    private var session:Session?=null
    private var installRequested=false
    private var background:CameraBackground?=null
    private var width=1
    private var height=1
    private val executor=Executors.newSingleThreadExecutor()
    private val imageBusy=AtomicBoolean(false)
    private val command=java.util.concurrent.atomic.AtomicInteger(0)
    private var directory:File?=null
    private var poses:BufferedWriter?=null
    private var count=0
    private var valid=0
    private var keyframes=0
    private var previousTimestamp=0L
    private var lastCandidate=0L
    private var started=0L
    @Volatile private var recording=false
    private var lastPose:Pose?=null
    private var configInfo=JSONObject()
    private var lastUi=0L
    private var depthSupported=false
    private var lastCloud=0L
    private val points=linkedMapOf<Int,List<Float>>()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        status=TextView(this).apply {text="初始化空间跟踪…";textSize=15f;setPadding(16,16,16,16)}
        layout.addView(status)
        surface=GLSurfaceView(this).apply {setEGLContextClientVersion(2);preserveEGLContextOnPause=true;setRenderer(this@CaptureActivity)}
        layout.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        button=Button(this).apply {text="开始录制";setOnClickListener {
            if(session==null){message("相机尚未就绪");return@setOnClickListener}
            isEnabled=false;command.set(if(recording)2 else 1)
        }}
        layout.addView(button);setContentView(layout)
    }

    override fun onResume(){
        super.onResume()
        try {
            if(session==null) {
                if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;return}
                val created=Session(this)
                val configs=created.getSupportedCameraConfigs(CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
                val chosen=configs.filter {it.imageSize.width<=1920}.maxByOrNull {it.imageSize.width*it.imageSize.height}
                if(chosen!=null)created.cameraConfig=chosen
                depthSupported=created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                created.configure(Config(created).apply {focusMode=Config.FocusMode.AUTO
                    // P0 measures RGB + poses first; depth is probed without adding capture load.
                    depthMode=Config.DepthMode.DISABLED
                    updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE})
                val config=created.cameraConfig
                configInfo=JSONObject().put("image_width",config.imageSize.width).put("image_height",config.imageSize.height)
                    .put("texture_width",config.textureSize.width).put("texture_height",config.textureSize.height)
                    .put("camera_id",config.cameraId).put("depth_supported",depthSupported)
                    .put("coordinate_system","ARCore right-handed; camera looks -Z; pose is camera-to-world")
                File(filesDir,"arcore-session.json").writeText(configInfo.toString(2))
                session=created
            }
            session?.resume();surface.onResume()
        }catch(e:Exception){message("空间跟踪不可用：${e.javaClass.simpleName} ${e.message}")
            File(filesDir,"arcore-error.txt").writeText(e.stackTraceToString())}
    }

    override fun onPause(){
        surface.onPause()
        if(recording)stopRecording()
        session?.pause()
        super.onPause()
    }
    override fun onDestroy(){session?.close();executor.shutdown();super.onDestroy()}

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){background=CameraBackground()}
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){width=w;height=h;GLES20.glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val active=session?:return
        try {
            val bg=background?:return
            active.setCameraTextureName(bg.texture)
            active.setDisplayGeometry(windowManager.defaultDisplay.rotation,width,height)
            val frame=active.update()
            bg.draw(frame)
            when(command.getAndSet(0)){1->startRecording(active);2->stopRecording()}
            val tracking=frame.camera.trackingState==TrackingState.TRACKING
            val now=SystemClock.elapsedRealtime()
            if(now-lastUi>500){lastUi=now;message("${if(tracking) "跟踪正常" else "请缓慢移动，对准有纹理的区域"}\n${if(recording) "已录制 ${(now-started)/1000} 秒 · 关键帧 $keyframes" else "就绪：点击开始，围绕静态目标走动"}\n图像 ${configInfo.optInt("image_width")}×${configInfo.optInt("image_height")} · 深度支持 $depthSupported")}
            if(!recording||frame.timestamp==previousTimestamp)return
            previousTimestamp=frame.timestamp;count++
            val camera=frame.camera
            if(tracking)valid++
            if(tracking&&now-lastCloud>1000){
                lastCloud=now
                frame.acquirePointCloud().use {cloud ->
                    val ids=cloud.ids;val coordinates=cloud.points
                    while(ids.hasRemaining()&&coordinates.remaining()>=4){
                        val id=ids.get();val x=coordinates.get();val y=coordinates.get();val z=coordinates.get();val confidence=coordinates.get()
                        if(confidence>.5f&&(points.containsKey(id)||points.size<20000))points[id]=listOf(x,y,z,confidence)
                    }
                }
            }
            val pose=camera.pose
            val intrinsics=camera.imageIntrinsics
            val row=JSONObject().put("timestamp_ns",frame.timestamp).put("tracking",camera.trackingState.name)
                .put("translation",JSONArray(pose.translation.toList())).put("quaternion_xyzw",JSONArray(pose.rotationQuaternion.toList()))
                .put("focal_length",JSONArray(intrinsics.focalLength.toList())).put("principal_point",JSONArray(intrinsics.principalPoint.toList()))
                .put("image_dimensions",JSONArray(intrinsics.imageDimensions.toList()))
            poses?.apply{write(row.toString());newLine();if(count%30==0)flush()}
            if(tracking&&now-lastCandidate>=500&&keyframes<600&&!imageBusy.get()) {
                val previous=lastPose
                val moved=previous==null||distance(previous,pose)>.025f||rotationDifference(previous,pose)>.004f||now-lastCandidate>3000
                if(moved&&imageBusy.compareAndSet(false,true)) {
                    try {
                        val image=frame.acquireCameraImage()
                        val destination=directory!!
                        lastCandidate=now;lastPose=pose
                        val metadata=JSONObject(row.toString()).put("image_timestamp_ns",image.timestamp)
                        executor.execute {
                            try {
                                val name="frame_${image.timestamp}"
                                val bytes=nv21(image)
                                val quality=sharpness(bytes,image.width,image.height)
                                if(quality>2.0) {
                                    val temporary=File(destination,"$name.jpg.part")
                                    temporary.outputStream().use {out->check(YuvImage(bytes,ImageFormat.NV21,image.width,image.height,null)
                                        .compressToJpeg(Rect(0,0,image.width,image.height),90,out))}
                                    val target=File(destination,"$name.jpg")
                                    check(temporary.renameTo(target))
                                    metadata.put("file",target.name).put("width",image.width).put("height",image.height).put("sharpness",quality)
                                    File(destination,"$name.json").writeText(metadata.toString())
                                    keyframes++
                                }
                            }catch(e:Exception){File(destination,"image-error.txt").writeText(e.toString())}
                            finally{image.close();imageBusy.set(false)}
                        }
                    }catch(_:Exception){imageBusy.set(false)}
                }
            }
        }catch(e:Exception){message("采集异常：${e.javaClass.simpleName}: ${e.message}")}
    }

    private fun startRecording(active:Session) {
        try {
            val dest=File(filesDir,"captures/${System.currentTimeMillis()}").apply{mkdirs()}
            val recordingConfig=RecordingConfig(active).setMp4DatasetUri(Uri.fromFile(File(dest,"recording.mp4"))).setAutoStopOnPause(true)
            active.startRecording(recordingConfig)
            directory=dest;poses=File(dest,"poses.jsonl").bufferedWriter()
            count=0;valid=0;keyframes=0;previousTimestamp=0;lastCandidate=0;lastPose=null
            lastCloud=0;points.clear()
            started=SystemClock.elapsedRealtime();recording=true
            runOnUiThread{button.text="结束并保存";button.isEnabled=true}
        }catch(e:Exception){message("无法开始录制：${e.message}");runOnUiThread{button.isEnabled=true}}
    }

    private fun stopRecording() {
        if(!recording)return
        recording=false
        try {session?.stopRecording()}catch(_:Exception){}
        poses?.close();poses=null
        val dest=directory?:return
        val duration=SystemClock.elapsedRealtime()-started
        val cloud=points.values.map{it.toList()}
        executor.execute {
            val frames=JSONArray()
            dest.listFiles()?.filter{it.name.startsWith("frame_")&&it.extension=="json"}?.sortedBy{it.name}
                ?.forEach{frames.put(JSONObject(it.readText()))}
            val report=JSONObject(configInfo.toString()).put("duration_ms",duration).put("frames",count)
                .put("tracking_frames",valid).put("keyframes",frames)
                .put("video","recording.mp4").put("video_kind","ARCore CPU dataset recording")
                .put("schema_version",1).put("depth_recorded",false)
                .put("sparse_points",JSONArray(cloud.map{JSONArray(it)}))
            val pending=File(dest,"capture.json.part");pending.writeText(report.toString(2));check(pending.renameTo(File(dest,"capture.json")))
            message("已保存 ${(duration/1000)} 秒\n有效跟踪 $valid/$count · 关键帧 ${frames.length()}\n返回首页可上传；原视频保留手机。")
            runOnUiThread{button.text="开始新录制";button.isEnabled=true}
        }
    }

    private fun message(value:String){runOnUiThread{status.text=value}}
    private fun distance(a:Pose,b:Pose):Float {val x=a.tx()-b.tx();val y=a.ty()-b.ty();val z=a.tz()-b.tz();return kotlin.math.sqrt(x*x+y*y+z*z)}
    private fun rotationDifference(a:Pose,b:Pose):Float {val x=a.rotationQuaternion;val y=b.rotationQuaternion;return 1f-kotlin.math.abs(x.indices.sumOf{(x[it]*y[it]).toDouble()}.toFloat())}
    private fun sharpness(bytes:ByteArray,width:Int,height:Int):Double {
        var sum=0L;var count=0
        for(y in 4 until height-4 step 8)for(x in 4 until width-4 step 8){val i=y*width+x
            sum+=kotlin.math.abs((bytes[i].toInt() and 255)-(bytes[i+2].toInt() and 255));count++}
        return sum.toDouble()/count.coerceAtLeast(1)
    }
    private fun nv21(image:Image):ByteArray {
        val width=image.width;val height=image.height
        val data=ByteArray(width*height*3/2)
        image.planes.forEachIndexed {index,plane ->
            val buffer=plane.buffer;val base=buffer.position();val w=if(index==0)width else width/2;val h=if(index==0)height else height/2
            for(y in 0 until h)for(x in 0 until w){val source=base+y*plane.rowStride+x*plane.pixelStride
                val target=if(index==0)y*width+x else width*height+y*width+x*2+if(index==1)1 else 0
                data[target]=buffer.get(source)}
        }
        return data
    }
}

private class CameraBackground {
    val texture:Int
    private val program:Int
    private val vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply{put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)}
    private val uv=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    init {
        val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);texture=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        fun shader(type:Int,source:String):Int {val shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader)
            val compiled=IntArray(1);GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);check(compiled[0]!=0){GLES20.glGetShaderInfoLog(shader)};return shader}
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPos,0.,1.);vUv=aUv;}"))
        GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 vUv; void main(){gl_FragColor=texture2D(camera,vUv);}"))
        GLES20.glLinkProgram(program)
    }
    fun draw(frame:Frame) {
        if(frame.timestamp==0L)return
        vertices.position(0);uv.position(0)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,vertices,Coordinates2d.TEXTURE_NORMALIZED,uv)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"camera"),0)
        val a=GLES20.glGetAttribLocation(program,"aPos");val b=GLES20.glGetAttribLocation(program,"aUv")
        vertices.position(0);uv.position(0)
        GLES20.glEnableVertexAttribArray(a);GLES20.glEnableVertexAttribArray(b)
        GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(b,2,GLES20.GL_FLOAT,false,0,uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(a);GLES20.glDisableVertexAttribArray(b)
    }
}
