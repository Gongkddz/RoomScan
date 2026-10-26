package cn.roomscan.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File

class PlaybackActivity:Activity(){
    private var player:ExoPlayer?=null
    override fun onCreate(state:Bundle?){super.onCreate(state)
        val id=intent.getStringExtra("capture")?:run{finish();return}
        if(!id.matches(Regex("[0-9]+"))){finish();return}
        val path=File(filesDir,"captures/$id/recording.mp4")
        if(!path.exists()){finish();return}
        window.statusBarColor=Color.rgb(16,23,33);window.navigationBarColor=Color.rgb(16,23,33);window.decorView.systemUiVisibility=0
        val root=Ui.column(this).apply{setBackgroundColor(Color.rgb(16,23,33))};setContentView(root);Ui.insets(root)
        root.addView(Ui.label(this,"‹  录制回看",18f,Color.WHITE,true).apply{
            setPadding(Ui.dp(this@PlaybackActivity,20),Ui.dp(this@PlaybackActivity,14),0,Ui.dp(this@PlaybackActivity,14));setOnClickListener{finish()}})
        val view=layoutInflater.inflate(R.layout.playback,root,false) as PlayerView
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        player=ExoPlayer.Builder(this).build().also{engine->
            view.player=engine
            engine.addListener(object:Player.Listener{
                override fun onVideoSizeChanged(videoSize:VideoSize){
                    File(filesDir,"playback-report.json").writeText(JSONObject().put("width",videoSize.width).put("height",videoSize.height)
                        .put("unapplied_rotation",videoSize.unappliedRotationDegrees).put("pixel_ratio",videoSize.pixelWidthHeightRatio).toString())
                }
            })
            engine.setMediaItem(MediaItem.fromUri(Uri.fromFile(path)));engine.prepare();engine.playWhenReady=true
        }
    }
    override fun onPause(){player?.pause();super.onPause()}
    override fun onDestroy(){player?.release();player=null;super.onDestroy()}
}
