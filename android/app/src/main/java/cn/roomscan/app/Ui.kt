package cn.roomscan.app

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.*

object Ui {
    val ink=Color.rgb(22,35,58)
    val muted=Color.rgb(113,126,148)
    val blue=Color.rgb(57,99,235)
    val surface=Color.rgb(246,248,252)
    fun dp(c:Context,value:Int)=(value*c.resources.displayMetrics.density).toInt()
    fun rounded(color:Int,radius:Float=22f,stroke:Int?=null)=GradientDrawable().apply {
        setColor(color);cornerRadius=radius*android.content.res.Resources.getSystem().displayMetrics.density
        stroke?.let {setStroke(2,it)}
    }
    fun label(c:Context,value:String,size:Float=15f,color:Int=ink,bold:Boolean=false)=TextView(c).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun button(c:Context,value:String,action:()->Unit)=Button(c).apply {
        text=value;textSize=16f;isAllCaps=false;setTextColor(Color.WHITE)
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        background=rounded(blue,17f);minHeight=dp(c,54);stateListAnimator=null
        setOnClickListener{action()}
    }
    fun space(c:Context,height:Int)=View(c).apply{layoutParams=LinearLayout.LayoutParams(1,dp(c,height))}
    fun column(c:Context,padding:Int=0)=LinearLayout(c).apply {
        orientation=LinearLayout.VERTICAL
        val p=dp(c,padding);setPadding(p,p,p,p)
    }
    fun screen(activity:Activity){
        activity.window.statusBarColor=surface;activity.window.navigationBarColor=Color.WHITE
        activity.window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    fun insets(view:View){
        view.setOnApplyWindowInsetsListener{target,insets->
            val bars=insets.getInsets(WindowInsets.Type.systemBars())
            target.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        view.requestApplyInsets()
    }
}

class SpatialArtwork(c:Context):View(c) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        val w=width.toFloat();val h=height.toFloat();val cx=w*.5f;val cy=h*.48f;val size=minOf(w,h)*.3f
        paint.shader=RadialGradient(cx,cy,size*2,intArrayOf(0x559DC3FF,0x009DC3FF),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,size*2,paint);paint.shader=null
        val points=arrayOf(PointF(cx,cy-size),PointF(cx+size,cy-size*.45f),PointF(cx+size,cy+size*.7f),
            PointF(cx,cy+size*1.25f),PointF(cx-size,cy+size*.7f),PointF(cx-size,cy-size*.45f),PointF(cx,cy+size*.1f))
        fun face(ids:IntArray,color:Int){paint.color=color;paint.style=Paint.Style.FILL
            val path=Path();path.moveTo(points[ids[0]].x,points[ids[0]].y);ids.drop(1).forEach{path.lineTo(points[it].x,points[it].y)};path.close();canvas.drawPath(path,paint)}
        face(intArrayOf(0,1,6,5),0xBBD7E8FF.toInt());face(intArrayOf(5,6,3,4),0xAA8FB5FF.toInt());face(intArrayOf(6,1,2,3),0xAA83DBC9.toInt())
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f;paint.color=0xAAFFFFFF.toInt()
        for((a,b)in listOf(0 to 1,1 to 2,2 to 3,3 to 4,4 to 5,5 to 0,5 to 6,1 to 6,6 to 3))canvas.drawLine(points[a].x,points[a].y,points[b].x,points[b].y,paint)
        paint.style=Paint.Style.FILL
        for(i in 0..42){val angle=i*2.399963f;val radius=size*(.2f+(i%9)*.13f)
            paint.color=if(i%3==0)0xAA75CCB7.toInt() else 0xAA81A8F9.toInt()
            canvas.drawCircle(cx+kotlin.math.cos(angle)*radius,cy+kotlin.math.sin(angle)*radius*.8f,if(i%5==0)4f else 2f,paint)}
    }
}
