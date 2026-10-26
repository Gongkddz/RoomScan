package cn.roomscan.app

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.abs
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONObject

data class CaptureLens(val deviceId:String,val physicalId:String?,val info:CameraCharacteristics,val size:Size,val equivalent:Double){
    val label:String get()=when{equivalent<20->"超广角";equivalent<40->"广角";else->"长焦"}+" · ${equivalent.toInt()}mm"
    fun metadata()=JSONObject().put("device_id",deviceId).put("physical_id",physicalId).put("label",label)
        .put("equivalent_focal_mm",equivalent).put("width",size.width).put("height",size.height)
}

object CaptureLenses {
    fun discover(manager:CameraManager):List<CaptureLens>{
        val result=mutableListOf<CaptureLens>()
        val encoder=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull{it.isEncoder&&it.supportedTypes.any{type->type.equals("video/avc",true)}}
            ?.getCapabilitiesForType("video/avc")?.videoCapabilities
        fun add(deviceId:String,physicalId:String?,info:CameraCharacteristics){
            runCatching{
                if(info.get(CameraCharacteristics.LENS_FACING)!=CameraCharacteristics.LENS_FACING_BACK)return
                val map=info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:return
                val active=info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?:return
                val nativeRatio=active.width().toDouble()/active.height()
                val video=map.getOutputSizes(MediaRecorder::class.java)?.toSet()?:return
                val preview=map.getOutputSizes(SurfaceTexture::class.java)?.toSet()?:return
                val sizes=map.getOutputSizes(ImageFormat.YUV_420_888).filter{it in video&&it in preview&&it.width<=2560&&it.height<=1920&&
                    (encoder?.areSizeAndRateSupported(it.width,it.height,30.0)!=false)}
                // Aspect ratio determines field of view; resolution only breaks ties.
                val chosen=sizes.sortedWith(compareBy<Size>{abs(ln((it.width.toDouble()/it.height)/nativeRatio))}
                    .thenByDescending{it.width.toLong()*it.height}).firstOrNull()?:return
                val mm=info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?:return
                val focal=info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()?:return
                result.add(CaptureLens(deviceId,physicalId,info,chosen,focal/mm.width*36.0))
            }
        }
        manager.cameraIdList.forEach{id->
            val info=manager.getCameraCharacteristics(id)
            if(info.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK){
                val physical=info.physicalCameraIds
                if(physical.isEmpty())add(id,null,info)
                else physical.forEach{physicalId->add(id,physicalId,manager.getCameraCharacteristics(physicalId))}
            }
        }
        return result.sortedBy{it.equivalent}.distinctBy{(it.equivalent*2).toInt()}
    }
    fun report(lenses:List<CaptureLens>)=JSONArray().apply{lenses.forEach{put(it.metadata())}}
}
