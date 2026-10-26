package cn.roomscan.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class CaptureUpload(private val client:PinnedClient) {
    fun upload(directory:File,progress:(String)->Unit):JSONObject {
        val stateFile=File(directory,"upload.json")
        val state=if(stateFile.exists())JSONObject(stateFile.readText()) else JSONObject()
        val projectId=state.optString("project_id").ifEmpty {
            client.json("POST","/v1/projects",JSONObject().put("name",directory.name)).getString("project_id").also {
                state.put("project_id",it);stateFile.writeText(state.toString())
            }
        }
        val files=directory.listFiles()!!.filter { it.extension=="jpg" || it.name=="poses.jsonl" || it.name=="capture.json" }
            .sortedBy {it.name}
        require(files.any {it.extension=="jpg"}) {"未采集到有效图像"}
        val byHash=linkedMapOf<String,File>()
        for(file in files) {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {stream -> val buffer=ByteArray(65536);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            byHash[digest.digest().joinToString(""){"%02x".format(it)}]=file
        }
        val objects=JSONArray()
        byHash.forEach {(hash,file)->objects.put(JSONObject().put("sha256",hash).put("size",file.length()).put("kind",
            if(file.extension=="jpg")"image" else if(file.name=="poses.jsonl")"poses" else "calibration"))}
        val fileNames=JSONObject();byHash.forEach {(hash,file)->fileNames.put(hash,file.name)}
        val manifest=JSONObject().put("objects",objects).put("capture",JSONObject().put("files",fileNames).put("format","roomscan-p0"))
        client.json("PUT","/v1/projects/$projectId/manifest",manifest)
        val remote=client.json("GET","/v1/projects/$projectId/objects").getJSONArray("objects")
        var uploaded=0L
        for(index in 0 until remote.length()) {
            val info=remote.getJSONObject(index)
            if(info.getBoolean("complete"))continue
            val hash=info.getString("sha256");val file=byHash.getValue(hash)
            var offset=info.getLong("offset")
            RandomAccessFile(file,"r").use {source ->
                source.seek(offset)
                while(offset<file.length()) {
                    val buffer=ByteArray(minOf(1024L*1024,file.length()-offset).toInt());source.readFully(buffer)
                    val reply=client.request("PUT","/v1/projects/$projectId/objects/$hash?offset=$offset",buffer)
                    offset=reply.getLong("offset");uploaded+=buffer.size
                    progress("上传关键帧 ${index+1}/${remote.length()} · 本次 ${uploaded/1024} KB\n原视频保留在手机")
                }
            }
        }
        val result=client.json("POST","/v1/projects/$projectId/jobs")
        state.put("job_id",result.getString("id"));state.put("uploaded_bytes",uploaded);stateFile.writeText(state.toString())
        return result
    }
}

