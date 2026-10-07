package com.shilapi.xcertplay;
import android.location.Location;
import android.os.SystemClock;
import java.util.*;
/** Conservative accessory fixes. Never extrapolates or snaps coordinates to a lane. */
public final class LocationFixPolicy {
 private static Location accepted;
 private static volatile String decision="等待合格定位";
 private static long encodedNs=-1;
 private static boolean reportedValid;
 private static long validReports, invalidReports, duplicatePolls;
 public static synchronized String status(){return decision+"；有效报告="+validReports+"；失效报告="+invalidReports+"；重复采样跳过="+duplicatePolls;}
 public static synchronized void reset(){accepted=null;encodedNs=-1;reportedValid=false;validReports=invalidReports=duplicatePolls=0;decision="等待合格定位";}
 public static String rejection(Location f,long now){
  if(f==null)return "missing";
  if(!"gps".equals(f.getProvider())&&!"fused".equals(f.getProvider()))return "non-gnss-provider";
  long age=now-f.getElapsedRealtimeNanos();if(age<0||age>1500000000L)return "stale-or-future";
  if(!Double.isFinite(f.getLatitude())||!Double.isFinite(f.getLongitude())||Math.abs(f.getLatitude())>90||Math.abs(f.getLongitude())>180)return "invalid-coordinate";
  if(!f.hasAccuracy()||!Float.isFinite(f.getAccuracy())||f.getAccuracy()<=0||f.getAccuracy()>10)return "poor-or-unknown-accuracy";
  if(f.getTime()<=0)return "missing-utc";
  return null;
 }
 private static double speed(Location f){return f.hasSpeed()&&Float.isFinite(f.getSpeed())&&f.getSpeed()>=0?f.getSpeed():0;}
 private static double score(Location f,long now){return f.getAccuracy()+(now-f.getElapsedRealtimeNanos())/1e9*Math.max(1,speed(f));}
 public static synchronized Location select(Collection<Location> values,long now){
  Location best=null, known=null;StringBuilder reasons=new StringBuilder();
  for(Location f:values){String bad=rejection(f,now);if(bad==null&&accepted!=null){long dt=f.getElapsedRealtimeNanos()-accepted.getElapsedRealtimeNanos();
    if(dt<0)bad="out-of-order";
    else if(dt==0&&f.distanceTo(accepted)>f.getAccuracy()+accepted.getAccuracy())bad="conflicting-same-time";
    else if(dt>0&&now-accepted.getElapsedRealtimeNanos()<3000000000L){double limit=Math.max(60,Math.max(speed(f),speed(accepted))+15)*dt/1e9+3*(f.getAccuracy()+accepted.getAccuracy());if(f.distanceTo(accepted)>limit)bad="position-jump";}
   }
   if(bad!=null){reasons.append(f==null?"null":f.getProvider()).append(':').append(bad).append(';');continue;}
   // Prefer unsent valid fixes so a precise old sample cannot starve fresh updates.
   if(f.getElapsedRealtimeNanos()<=encodedNs){
    if(known==null||score(f,now)<score(known,now))known=f;
   }else if(best==null||score(f,now)<score(best,now)||score(f,now)==score(best,now)&&f.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos())best=f;
  }
  if(best==null)best=known;
  if(best==null){decision="暂停上报："+reasons;return null;}
  accepted=new Location(best);decision="选用 "+best.getProvider()+"；精度="+best.getAccuracy()+"m；年龄="+(now-best.getElapsedRealtimeNanos())/1000000+"ms；过滤="+reasons;
  return new Location(best);
 }
 public static synchronized String encode(Location f){
  if(f==null)return null;
  String bad=rejection(f,SystemClock.elapsedRealtimeNanos());if(bad!=null){decision="暂停上报："+bad;return null;}
  if(f.getElapsedRealtimeNanos()<=encodedNs){duplicatePolls++;decision="等待新坐标，不重复上报同一定位";return null;}
  Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US);utc.setTimeInMillis(f.getTime());
  String time=String.format(Locale.US,"%02d%02d%02d.%03d",utc.get(Calendar.HOUR_OF_DAY),utc.get(Calendar.MINUTE),utc.get(Calendar.SECOND),utc.get(Calendar.MILLISECOND));
  String date=String.format(Locale.US,"%02d%02d%02d",utc.get(Calendar.DAY_OF_MONTH),utc.get(Calendar.MONTH)+1,utc.get(Calendar.YEAR)%100);
  String lat=coordinate(f.getLatitude(),true),lon=coordinate(f.getLongitude(),false),ns=f.getLatitude()<0?"S":"N",ew=f.getLongitude()<0?"W":"E";
  int satellites=GnssTelemetry.satellitesFor(f);String sv=satellites<0?"":String.format(Locale.US,"%02d",Math.min(99,satellites));
  // Unknown HDOP and MSL altitude/geoid remain empty; Android altitude is ellipsoidal.
  String gga=String.join(",","GPGGA",time,lat,ns,lon,ew,"1",sv,"","","M","","M","","");
  String velocity=f.hasSpeed()&&Float.isFinite(f.getSpeed())&&f.getSpeed()>=0?String.format(Locale.US,"%.2f",f.getSpeed()*1.94384449):"";
  String heading=f.hasBearing()&&Float.isFinite(f.getBearing())&&f.getBearing()>=0&&f.getBearing()<360&&(!f.hasBearingAccuracy()||f.getBearingAccuracyDegrees()>=0&&f.getBearingAccuracyDegrees()<=30)&&speed(f)>=1?String.format(Locale.US,"%.2f",f.getBearing()):"";
  String rmc=String.join(",","GPRMC",time,"A",lat,ns,lon,ew,velocity,heading,date,"","");
  String result=sentence(gga)+sentence(rmc);encodedNs=f.getElapsedRealtimeNanos();reportedValid=true;validReports++;RuntimeDiagnostics.nmea(result);return result;
 }
 /** Produces one void pair on fix loss; never invents position or movement. */
 public static synchronized String report(Collection<Location> values){
  long now=SystemClock.elapsedRealtimeNanos();
  Location fix=select(values,now);
  RuntimeDiagnostics.selection(values,fix,now);
  String result=encode(fix);
  if(result!=null)return result;
  if(fix!=null&&rejection(fix,SystemClock.elapsedRealtimeNanos())==null)return null;
  if(!reportedValid)return null;
  reportedValid=false;invalidReports++;
  String invalid=sentence("GPGGA,,,,,,0,,,,,,,,")+sentence("GPRMC,,V,,,,,,,,,");
  decision="定位暂不可用，已生成无效定位标记";
  RuntimeDiagnostics.nmea(invalid);
  return invalid;
 }
 private static String coordinate(double degrees,boolean latitude){long units=Math.round(Math.abs(degrees)*60*100000);long whole=units/6000000;double minutes=(units%6000000)/100000.0;return String.format(Locale.US,latitude?"%02d%08.5f":"%03d%08.5f",whole,minutes);}
 private static String sentence(String body){int sum=0;for(int i=0;i<body.length();i++)sum^=body.charAt(i);return "$"+body+String.format(Locale.US,"*%02X\r\n",sum);}
}
