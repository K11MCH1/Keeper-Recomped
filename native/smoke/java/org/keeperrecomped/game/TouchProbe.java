package org.keeperrecomped.game;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;

/** Isolated test-APK helper. Shell-only permission; never packaged in the launcher. */
public final class TouchProbe implements AutoCloseable {
    private static native void snapshot();
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){perform(intent);}
    };
    private TouchProbe(Activity activity){
        this.activity=activity;
        activity.registerReceiver(receiver,new IntentFilter(activity.getPackageName()+".DEBUG_TOUCH"),"android.permission.DUMP",null,Context.RECEIVER_EXPORTED);
    }
    public static AutoCloseable install(Activity activity){return new TouchProbe(activity);}
    @Override public void close(){handler.removeCallbacksAndMessages(null);activity.unregisterReceiver(receiver);}
    private void send(long down,int action,float[] xs,float[] ys){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[xs.length];
        for(int i=0;i<xs.length;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=xs[i];coords[i].y=ys[i];coords[i].pressure=1;coords[i].size=.1f;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xs.length,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        activity.dispatchTouchEvent(event);event.recycle();
    }
    private void schedule(final long down,int delay,final int action,final float[] x,final float[] y){handler.postDelayed(()->send(down,action,x,y),delay);}
    private void perform(Intent intent){
        int w=activity.getWindow().getDecorView().getWidth(),h=activity.getWindow().getDecorView().getHeight();
        final float x=intent.getFloatExtra("x",.6f)*w,y=intent.getFloatExtra("y",.5f)*h;
        final float dx=intent.getFloatExtra("dx",.12f)*w,dy=intent.getFloatExtra("dy",0)*h;
        String scenario=intent.getStringExtra("scenario");if(scenario==null)scenario="tap";
        if(scenario.equals("snapshot")){snapshot();return;}
        final long down=SystemClock.uptimeMillis();
        if(scenario.equals("tap")||scenario.equals("hold")||scenario.equals("pan")){
            schedule(down,0,MotionEvent.ACTION_DOWN,new float[]{x},new float[]{y});
            if(scenario.equals("pan"))for(int i=1;i<=8;i++)schedule(down,i*40,MotionEvent.ACTION_MOVE,new float[]{x+dx*i/8},new float[]{y+dy*i/8});
            int duration=scenario.equals("hold")?700:scenario.equals("pan")?350:90;
            schedule(down,duration,MotionEvent.ACTION_UP,new float[]{x+(scenario.equals("pan")?dx:0)},new float[]{y+(scenario.equals("pan")?dy:0)});
            return;
        }
        float radius=Math.min(w,h)*.09f;
        schedule(down,0,MotionEvent.ACTION_DOWN,new float[]{x-radius},new float[]{y});
        schedule(down,20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-radius,x+radius},new float[]{y,y});
        float endRadius=radius,endAngle=0;
        if(scenario.equals("pinch")||scenario.equals("rotate")){
            boolean pinch=scenario.equals("pinch");
            float amount=intent.getFloatExtra("amount",pinch?.4f:30f);
            for(int i=1;i<=10;i++){
                float angle=pinch?0:(float)Math.toRadians(amount*i/10);
                float r=pinch?radius*(1+amount*i/10):radius;
                float rx=(float)Math.cos(angle)*r,ry=(float)Math.sin(angle)*r;
                schedule(down,20+i*35,MotionEvent.ACTION_MOVE,new float[]{x-rx,x+rx},new float[]{y-ry,y+ry});
                endRadius=r;endAngle=angle;
            }
        }
        float rx=(float)Math.cos(endAngle)*endRadius,ry=(float)Math.sin(endAngle)*endRadius;
        int duration=scenario.equals("twohold")?700:scenario.equals("twoTap")?130:400;
        schedule(down,duration,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-rx,x+rx},new float[]{y-ry,y+ry});
        schedule(down,duration+20,MotionEvent.ACTION_UP,new float[]{x-rx},new float[]{y-ry});
    }
}
