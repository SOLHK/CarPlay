package com.shilapi.xcertplay;
import android.content.Context;
import android.media.*;
import android.location.Location;
import android.os.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.util.*;
/** Read-only bounded diagnostics; no socket reads, routing changes or recovery. */
public final class RuntimeDiagnostics {
 private static Handler worker; private static File directory; private static AudioManager audio;
 private static final Map<AudioTrack,AudioAttributes> configured=Collections.synchronizedMap(new WeakHashMap<AudioTrack,AudioAttributes>());
 public static void track(AudioTrack t,AudioAttributes attributes){configured.put(t,attributes);track(t);}
 private static final List<WeakReference<AudioTrack>> tracks=new ArrayList<>();
 private static volatile WeakReference<Socket> socket=new WeakReference<>(null);
 private static volatile WeakReference<Thread> reader=new WeakReference<>(null);
 private static volatile String phase="inactive", gps="selection not requested", nmea="not generated";
 private static volatile long phaseAt, received, submitted, drained, rendered, gpsAt, nmeaAt;
 private static boolean interrupted;
 private static long now(){return SystemClock.elapsedRealtime();}
 public static synchronized void start(Context c){
  if(worker!=null)return;
  try {
   Context app=c.getApplicationContext();directory=new File(app.getFilesDir(),"logs");audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
   HandlerThread t=new HandlerThread("CarPlay-Diagnostics",android.os.Process.THREAD_PRIORITY_BACKGROUND);t.start();worker=new Handler(t.getLooper());
   worker.post(new Runnable(){public void run(){try{snapshot();}catch(RuntimeException e){log("snapshot unavailable="+e.getClass().getSimpleName());}finally{worker.postDelayed(this,5000);}}});
  }catch(RuntimeException e){android.util.Log.w("CarPlayDiagnostics","init unavailable",e);}
 }
 private static void log(String s){if(directory!=null)HourDiagnosticLog.append(directory,"Diagnostics: "+s);}
 public static synchronized void track(AudioTrack t){for(Iterator<WeakReference<AudioTrack>> i=tracks.iterator();i.hasNext();){AudioTrack old=i.next().get();if(old==null||old.getState()!=AudioTrack.STATE_INITIALIZED)i.remove();}if(tracks.size()==8)tracks.remove(0);tracks.add(new WeakReference<>(t));
 if(worker!=null){final WeakReference<AudioTrack> ref=new WeakReference<>(t);worker.postDelayed(new Runnable(){public void run(){AudioTrack live=ref.get();if(live!=null)audioSnapshot(live);}},250);
 try{t.addOnRoutingChangedListener(new AudioRouting.OnRoutingChangedListener(){public void onRoutingChanged(AudioRouting routing){AudioTrack live=ref.get();if(live!=null)audioSnapshot(live);}},worker);}catch(RuntimeException ignored){}
 }}
 public static void screen(Socket s){socket=new WeakReference<>(s);reader=new WeakReference<>(Thread.currentThread());received=submitted=drained=rendered=0;phase="connected";phaseAt=now();interrupted=false;}
 public static void stage(String s){if(reader.get()!=Thread.currentThread())return;phase=s;phaseAt=now();if("dispatch".equals(s))received=phaseAt;}
 public static void submitted(){submitted=now();}
 public static void draining(){drained=now();}
 public static void rendered(){rendered=now();}
 private static long age(long value,long n){return value==0?-1:Math.max(0,n-value);}
 public static void selection(Collection<Location> values,Location best,long ns){
  long n=now();if(n-gpsAt<5000)return;gpsAt=n;
  StringBuilder b=new StringBuilder("selected=").append(best==null?"none":best.getProvider()).append(" candidates=[");
  for(Location f:values){if(f==null)continue;b.append(f.getProvider()).append(":ageMs=").append((ns-f.getElapsedRealtimeNanos())/1000000).append(",accuracyM=").append(f.hasAccuracy()?f.getAccuracy():"unknown").append(';');}
  gps=b.append("] policy=quality-and-age,maxAgeMs=1500,maxAccuracyM=10; receiverGPS=Android-local; iPhoneGPS=not-exposed-by-CarPlay; NMEA satellites=observed-gps-only,HDOP=unknown-blank").toString();
 }
 public static void nmea(String value){long n=now();if(n-nmeaAt<5000)return;nmeaAt=n;nmea=value==null?"none":value.replace("\r", "").replace("\n", " | ");}
 private static void snapshot(){
  long n=now();Socket s=socket.get();Thread r=reader.get();boolean active=s!=null&&!s.isClosed()&&r!=null&&r.isAlive();
  if(s!=null){long displayAge=age(rendered,n);boolean stalled=active&&((rendered==0?age(phaseAt,n):displayAge)>3000);
   log("video active="+active+" phase="+phase+" phaseAgeMs="+age(phaseAt,n)+" reader="+(r==null?"gone":r.getState())+" receiveAgeMs="+age(received,n)+" submitAgeMs="+age(submitted,n)+" drainAgeMs="+age(drained,n)+" displaySubmitAgeMs="+displayAge+" outputGap="+stalled+" (idle_or_stall_not_proof_of_freeze)");
   if(stalled&&!interrupted&&r!=null)log("video reader stack="+Arrays.toString(r.getStackTrace()));interrupted=stalled;
  }
  log("location "+gps+" decision="+LocationFixPolicy.status()+" selectionSnapshotAgeMs="+age(gpsAt,n)+" encodedNmea="+nmea+" encodedAgeMs="+age(nmeaAt,n)+" (generated_not_phone_acceptance)");
  log(GnssTelemetry.describe());
  List<WeakReference<AudioTrack>> copy; synchronized(RuntimeDiagnostics.class){copy=new ArrayList<>(tracks);}
  for(WeakReference<AudioTrack> ref:copy){AudioTrack t=ref.get();if(t==null)continue;
   audioSnapshot(t);
  }
 }
 private static void audioSnapshot(AudioTrack t){
   try{if(t.getState()!=AudioTrack.STATE_INITIALIZED)return;AudioAttributes a=android.os.Build.VERSION.SDK_INT>=29?t.getAudioAttributes():configured.get(t);if(a==null){log("audio attributes unavailable api="+android.os.Build.VERSION.SDK_INT);return;}int stream=a.getVolumeControlStream();AudioDeviceInfo d=t.getRoutedDevice();AudioDeviceInfo preferred=t.getPreferredDevice();
    log("audio session="+t.getAudioSessionId()+" usage="+a.getUsage()+" content="+a.getContentType()+" volumeControlStream="+stream+" volume="+(audio!=null&&stream>=0?audio.getStreamVolume(stream):-1)+" max="+(audio!=null&&stream>=0?audio.getStreamMaxVolume(stream):-1)+" mute="+(audio!=null&&stream>=0?audio.isStreamMute(stream):"unknown")+" route="+device(d)+" preferred="+device(preferred)+" playState="+t.getPlayState()+" underruns="+t.getUnderrunCount()+" systemMode="+(audio==null?-1:audio.getMode())+" vendorVolumeGroup=unavailable (stream_is_system_mapping_not_vendor_group_proof)");
   }catch(RuntimeException e){log("audio unavailable="+e.getClass().getSimpleName());}
 }
 private static String device(AudioDeviceInfo d){return d==null?"unavailable":"id:"+d.getId()+",type:"+d.getType();}
}
