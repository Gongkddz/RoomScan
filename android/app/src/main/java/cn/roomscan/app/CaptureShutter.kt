package cn.roomscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.Button

/** Native accessible button with camera-style record/stop/done states. */
class CaptureShutter(context:Context):Button(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init{background=null;minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0;stateListAnimator=null;setPadding(0,0,0,0)}
    override fun onDraw(canvas:Canvas){
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)/2f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=Ui.dp(context,2).toFloat();paint.color=0xFFBACCF9.toInt();canvas.drawCircle(cx,cy,radius-paint.strokeWidth,paint)
        paint.style=Paint.Style.FILL;paint.color=if(!isEnabled)0xFF9EB4EC.toInt() else if(isPressed)0xFF2D50C4.toInt() else Ui.blue
        canvas.drawCircle(cx,cy,radius-Ui.dp(context,7),paint)
        if(text.contains("结束")){paint.color=0xFFFFFFFF.toInt();val half=Ui.dp(context,10).toFloat();canvas.drawRoundRect(cx-half,cy-half,cx+half,cy+half,Ui.dp(context,4).toFloat(),Ui.dp(context,4).toFloat(),paint)}
        else if(text=="完成"){paint.color=0xFFFFFFFF.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=Ui.dp(context,3).toFloat();paint.strokeCap=Paint.Cap.ROUND;canvas.drawPath(Path().apply{moveTo(cx-Ui.dp(context,9),cy);lineTo(cx-Ui.dp(context,2),cy+Ui.dp(context,7));lineTo(cx+Ui.dp(context,11),cy-Ui.dp(context,8))},paint)}
        else {paint.color=0xFFFFFFFF.toInt();canvas.drawCircle(cx,cy,Ui.dp(context,9).toFloat(),paint)}
        paint.strokeCap=Paint.Cap.BUTT
    }
}
