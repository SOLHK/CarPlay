package com.shilapi.xcertplay;
import android.content.Context;import android.util.DisplayMetrics;import android.util.TypedValue;import android.widget.TextView;
public final class ResponsiveUi {
 public static float density(Context c){DisplayMetrics m=c.getResources().getDisplayMetrics();return factor(m.widthPixels,m.heightPixels,m.density);}
 public static float factor(int w,int h,float d){int edge=Math.min(w,h);if(edge<=0)return d;return Math.max(.85f,Math.min(d,Math.min(1.5f,edge/720f)));}
 public static int dp(Context c,int value){return Math.round(value*density(c));}
 public static void text(TextView view,float sp){float size=Math.max(14,Math.min(28,sp));view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*density(view.getContext()));}
}
