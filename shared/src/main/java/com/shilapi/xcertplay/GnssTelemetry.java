package com.shilapi.xcertplay;
import android.location.*;import android.os.*;import android.util.Log;import java.util.*;
public final class GnssTelemetry {
 private static LocationManager owner;private static HandlerThread thread;private static GnssStatus.Callback callback;
 private static volatile int visible,used,beidou;private static volatile long observed;private static volatile String state="未启动";
 private static volatile Location latest;private static volatile long fixes;
 public static synchronized void start(final LocationManager manager){
  if(owner==manager||manager==null)return;stop(owner);
  // Location callbacks must keep a worker even when raw satellite status is denied.
  owner=manager;thread=new HandlerThread("CarPlay-GNSS",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();
  GnssStatus.Callback cb=new GnssStatus.Callback(){public void onSatelliteStatusChanged(GnssStatus s){if(owner!=manager||callback!=this)return;int u=0,b=0;for(int i=0;i<s.getSatelliteCount();i++){if(s.usedInFix(i))u++;if(s.getConstellationType(i)==GnssStatus.CONSTELLATION_BEIDOU)b++;}visible=s.getSatelliteCount();used=u;beidou=b;observed=SystemClock.elapsedRealtime();state="卫星状态更新";}public void onStopped(){if(owner==manager&&callback==this)state="无卫星更新";}};
  try{if(manager.registerGnssStatusCallback(cb,new Handler(thread.getLooper()))){callback=cb;state="等待接收设备卫星";}else state="卫星状态不可用；坐标订阅独立运行";}
  catch(RuntimeException e){state="卫星权限或接口不可用；坐标订阅独立运行";Log.w("CarPlayLocation",state,e);}
 }
 public static synchronized void stop(LocationManager m){if(owner==null||owner!=m)return;try{if(callback!=null)owner.unregisterGnssStatusCallback(callback);}catch(RuntimeException ignored){}finally{callback=null;owner=null;if(thread!=null)thread.quitSafely();thread=null;visible=used=beidou=0;observed=0;latest=null;fixes=0;state="已停止";LocationFixPolicy.reset();}}
 public static synchronized Looper looper(){if(thread==null)throw new IllegalStateException("Location worker has not started");return thread.getLooper();}
 private static boolean valid(Location fix,long now){if(fix==null)return false;long age=now-fix.getElapsedRealtimeNanos();return age>=0&&age<=5000000000L&&Double.isFinite(fix.getLatitude())&&Double.isFinite(fix.getLongitude())&&Math.abs(fix.getLatitude())<=90&&Math.abs(fix.getLongitude())<=180&&(!fix.hasAccuracy()||(Float.isFinite(fix.getAccuracy())&&fix.getAccuracy()>=0));}
 public static void onFix(Location fix){if(!valid(fix,SystemClock.elapsedRealtimeNanos()))return;Location previous=latest;if(previous==null||fix.getElapsedRealtimeNanos()>=previous.getElapsedRealtimeNanos()){latest=new Location(fix);fixes++;}}
 public static Location select(Collection<Location> values){long now=SystemClock.elapsedRealtimeNanos();Location best=LocationFixPolicy.select(values,now);RuntimeDiagnostics.selection(values,best,now);return best;}
 public static int satellitesFor(Location f){long age=SystemClock.elapsedRealtime()-observed;return f!=null&&"gps".equals(f.getProvider())&&observed>0&&age>=0&&age<=1500?used:-1;}
 public static String describe(){
  long age=observed==0?-1:SystemClock.elapsedRealtime()-observed;
  String text="接收设备GPS："+state+"；卫星可见="+visible+"；参与定位="+used+"；北斗可见="+beidou+"；卫星状态年龄ms="+age+"\n";
  Location f=latest;if(f==null)text+="实时坐标：等待系统定位（需要精确位置权限及接收设备定位开启）\n";
  else {long ms=(SystemClock.elapsedRealtimeNanos()-f.getElapsedRealtimeNanos())/1000000L;boolean fresh=ms>=0&&ms<=5000;
   text+=String.format(Locale.US,"%s：纬度 %.6f，经度 %.6f；来源 %s\n",fresh?"最近采集坐标（不代表已上报）":"采集坐标已过期",f.getLatitude(),f.getLongitude(),f.getProvider());
   text+="精度 "+(f.hasAccuracy()?String.format(Locale.US,"%.1f米",f.getAccuracy()):"未知")+"；速度 "+(f.hasSpeed()?String.format(Locale.US,"%.1f公里/时",f.getSpeed()*3.6):"未知")+"；数据年龄 "+ms+"ms；回调 "+fixes+"次\n";
  }
  return text+"定位上报："+LocationFixPolicy.status()+"\n接收设备定位采集请求间隔500ms，实际频率由系统决定。\niPhone GPS：CarPlay未提供读取其实时坐标的接口；不能确认iPhone最终采用了哪个来源；接收设备坐标失效时通知一次，收到新合格坐标后恢复上报。\n";
 }
}
