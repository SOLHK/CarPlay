package com.shilapi.xcertplay.media
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
object CarPlayTouchMapper {
 private val ids = intArrayOf(-1,-1)
 private val xs = doubleArrayOf(0.0,0.0)
 private val ys = doubleArrayOf(0.0,0.0)
 @Synchronized fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> = contacts(event,CarPlayVideoLayout(0f,0f,viewWidth.toFloat(),viewHeight.toFloat()))
 @Synchronized fun contacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> {
  val action=event.actionMasked
  if(action==MotionEvent.ACTION_DOWN)ids.fill(-1)
  val cancel=action==MotionEvent.ACTION_CANCEL
  if(!cancel) for(p in 0 until event.pointerCount) { val id=event.getPointerId(p); if(id !in ids){val slot=ids.indexOf(-1);if(slot>=0)ids[slot]=id} }
  val result=(0..1).map { slot ->
   val index=if(ids[slot]<0)-1 else event.findPointerIndex(ids[slot])
   if(index>=0){val x=(event.getX(index)-content.left)/content.width.coerceAtLeast(1f);val y=(event.getY(index)-content.top)/content.height.coerceAtLeast(1f);if(x.isFinite()&&y.isFinite()){xs[slot]=x.toDouble().coerceIn(0.0,1.0);ys[slot]=y.toDouble().coerceIn(0.0,1.0)}}
   val down=index>=0&&!cancel&&action!=MotionEvent.ACTION_UP&&!(action==MotionEvent.ACTION_POINTER_UP&&index==event.actionIndex)
   AirPlayContact(slot,xs[slot],ys[slot],down).also { if(!down)ids[slot]=-1 }
  }
  if(cancel||action==MotionEvent.ACTION_UP)ids.fill(-1)
  return result
 }
}
