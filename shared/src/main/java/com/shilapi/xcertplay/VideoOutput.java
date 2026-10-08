package com.shilapi.xcertplay;
import android.media.MediaCodec;import android.view.Surface;import android.util.Log;import com.shilapi.xcertplay.media.VideoStats;
/** Drop only decoded output, retaining every compressed reference frame. */
public final class VideoOutput {
 private static long skipped,shown,staleDrains;
 // Never withhold every decoded frame during a sustained backlog.
 public static boolean drainWithAge(MediaCodec codec,Surface surface,VideoStats stats,long receivedNs){
  return drain(codec,surface,stats);
 }
 public static String describe(){return "视频输出合并：显示="+shown+"；跳过过时解码画面="+skipped+"；旧画面屏蔽已关闭="+staleDrains+"\n";}
 public static boolean drain(MediaCodec codec,Surface surface,VideoStats stats){
  return drain(codec,surface,stats,new MediaCodec.BufferInfo());
 }
 /** Each serial decoder reuses its own metadata rather than allocating at every idle poll. */
 public static boolean drain(MediaCodec codec,Surface surface,VideoStats stats,MediaCodec.BufferInfo info){
  RuntimeDiagnostics.draining();int pending=-1;boolean eos=false;int loops=0;long began=System.nanoTime();
  try {
   while(loops++<16&&System.nanoTime()-began<8000000L){
    int index=codec.dequeueOutputBuffer(info,0);
    if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break;
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){Log.i("xcertplay-usb","video output "+codec.getOutputFormat());continue;}
    if(index<0)continue;
    if(pending>=0){int old=pending;pending=-1;codec.releaseOutputBuffer(old,false);skipped++;}
    pending=index;eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
    if(eos)break;
   }
   if(pending>=0){int last=pending;pending=-1;boolean render=surface!=null;codec.releaseOutputBuffer(last,render);if(render){stats.onRendered();shown++;RuntimeDiagnostics.rendered();return true;}}
   return false;
  } finally {if(pending>=0)codec.releaseOutputBuffer(pending,false);}
 }
}
