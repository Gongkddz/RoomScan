package cn.roomscan.app

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.io.File

class PinnedClient(private val base:String,private val pin:String,private val token:String?=null) {
    private val context:SSLContext=SSLContext.getInstance("TLS").apply {
        init(null,arrayOf<TrustManager>(object:X509TrustManager {
            override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) { throw java.security.cert.CertificateException("Client certificates not used") }
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                if(chain.isEmpty())throw java.security.cert.CertificateException("Missing certificate")
                chain[0].checkValidity()
                val actual=MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString(""){"%02x".format(it)}
                if(actual!=pin)throw java.security.cert.CertificateException("Server identity changed")
            }
        }),null)
    }
    fun json(method:String,path:String,body:JSONObject?=null):JSONObject = request(method,path,body?.toString()?.toByteArray(),"application/json")
    fun request(method:String,path:String,body:ByteArray?=null,contentType:String="application/octet-stream"):JSONObject {
        val target=URL(base+path)
        require(target.protocol=="https") {"HTTPS required"}
        val connection=target.openConnection() as HttpsURLConnection
        connection.sslSocketFactory=context.socketFactory
        // Identity is verified by the explicitly confirmed certificate pin, not LAN DNS.
        connection.hostnameVerifier=HostnameVerifier {_,session ->
            val cert=session.peerCertificates.firstOrNull()?:return@HostnameVerifier false
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(""){"%02x".format(it)}==pin
        }
        connection.instanceFollowRedirects=false
        connection.connectTimeout=10000;connection.readTimeout=30000;connection.requestMethod=method
        token?.let {connection.setRequestProperty("Authorization","Bearer $it")}
        try {
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type",contentType)
                connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use{it.write(body)}}
            val code=connection.responseCode
            val content=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}"
            if(code !in 200..299)error("HTTP $code: ${content.take(240)}")
            return JSONObject(content)
        }finally{connection.disconnect()}
    }
    fun download(path:String,target:File,expected:String,maxBytes:Long=128L*1024*1024){
        require(expected.matches(Regex("[a-f0-9]{64}")))
        target.parentFile?.mkdirs()
        if(target.exists()&&sha256(target)==expected)return
        val partial=File(target.parentFile,target.name+".part")
        var offset=if(partial.exists())partial.length() else 0L
        val connection=URL(base+path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory=context.socketFactory
        connection.hostnameVerifier=HostnameVerifier{_,session->
            MessageDigest.getInstance("SHA-256").digest(session.peerCertificates[0].encoded).joinToString(""){"%02x".format(it)}==pin}
        connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=30000
        token?.let{connection.setRequestProperty("Authorization","Bearer $it")}
        if(offset>0)connection.setRequestProperty("Range","bytes=$offset-")
        try{
            val code=connection.responseCode
            require(code==200||code==206){"下载未完成，请重试"}
            if(code==200)offset=0
            else require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-")==true){"下载偏移不匹配"}
            java.io.FileOutputStream(partial,offset>0).use {output->
                connection.inputStream.use{input->val buffer=ByteArray(65536);var total=offset
                    while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=maxBytes){"模型超过本机缓存预算"};output.write(buffer,0,count)}}
            }
            if(sha256(partial)!=expected){partial.delete();error("模型校验失败，请重试")}
            if(target.exists())target.delete()
            check(partial.renameTo(target))
        }finally{connection.disconnect()}
    }
    private fun sha256(file:File):String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(65536)
        while(true){val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)}}
        return hash.digest().joinToString(""){"%02x".format(it)}}
}

