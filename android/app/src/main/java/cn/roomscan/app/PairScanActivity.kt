package cn.roomscan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

class PairScanActivity:Activity(){
    private lateinit var preview:TextureView
    private val thread=HandlerThread("pair-qr")
    private lateinit var handler:Handler
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null
    private var previewSurface:Surface?=null
    private var lastAttempt=0L
    private var done=false
    override fun onCreate(state:Bundle?){super.onCreate(state)
        window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK;window.decorView.systemUiVisibility=0
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)};setContentView(root);Ui.insets(root)
        preview=TextureView(this);root.addView(preview,FrameLayout.LayoutParams(-1,-1))
        val top=Ui.column(this,24).apply{setBackgroundColor(0xBB08101F.toInt())}
        top.addView(Ui.label(this,"‹  连接电脑",22f,Color.WHITE,true).apply{setOnClickListener{finish()}})
        top.addView(Ui.space(this,14));top.addView(Ui.label(this,"扫描电脑「留境」页面上的配对码\n首次配对后，程序会自动重连。",14f,Color.WHITE))
        root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val guide=View(this).apply{background=Ui.rounded(Color.TRANSPARENT,24f,0xCCFFFFFF.toInt())}
        root.addView(guide,FrameLayout.LayoutParams(Ui.dp(this,270),Ui.dp(this,270),Gravity.CENTER))
        thread.start();handler=Handler(thread.looper)
        preview.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(texture:SurfaceTexture,w:Int,h:Int){open()}
            override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,w:Int,h:Int){}
            override fun onSurfaceTextureDestroyed(texture:SurfaceTexture)=true
            override fun onSurfaceTextureUpdated(texture:SurfaceTexture){}
        }
    }
    @Suppress("MissingPermission","DEPRECATION")
    private fun open(){
        val manager=getSystemService(CameraManager::class.java)
        val id=manager.cameraIdList.first{manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}
        reader=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,2).apply{setOnImageAvailableListener({source ->
            val image=source.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                val now=SystemClock.elapsedRealtime();if(done||now-lastAttempt<250)return@setOnImageAvailableListener
                lastAttempt=now
                val plane=image.planes[0];val bytes=ByteArray(image.width*image.height);val buffer=plane.buffer;val base=buffer.position()
                for(y in 0 until image.height)for(x in 0 until image.width)bytes[y*image.width+x]=buffer.get(base+y*plane.rowStride+x*plane.pixelStride)
                val sourceY=PlanarYUVLuminanceSource(bytes,image.width,image.height,0,0,image.width,image.height,false)
                val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(sourceY)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                if(result.text.contains("roomscan-pair")){done=true;runOnUiThread{setResult(RESULT_OK,Intent().putExtra("payload",result.text));finish()}}
            }catch(_:NotFoundException){}catch(_:Exception){}
            finally{image.close()}
        },handler)}
        manager.openCamera(id,object:CameraDevice.StateCallback(){
            override fun onOpened(device:CameraDevice){camera=device
                val texture=preview.surfaceTexture?:return;texture.setDefaultBufferSize(1280,720);previewSurface=Surface(texture)
                val targets=listOf(previewSurface!!,reader!!.surface)
                device.createCaptureSession(targets,object:CameraCaptureSession.StateCallback(){
                    override fun onConfigured(configured:CameraCaptureSession){session=configured
                        val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{targets.forEach{addTarget(it)}
                            set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)}
                        configured.setRepeatingRequest(request.build(),null,handler)}
                    override fun onConfigureFailed(configured:CameraCaptureSession){}
                },handler)}
            override fun onDisconnected(device:CameraDevice){device.close()}
            override fun onError(device:CameraDevice,error:Int){device.close()}
        },handler)
    }
    override fun onDestroy(){handler.post{session?.close();camera?.close();reader?.close();previewSurface?.release();thread.quitSafely()};super.onDestroy()}
}
