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
    /**
     * Free-form fingers (Claude, 2026-10-29), each its own pointer id, window pixels and ms:
     * "L:x0,y0,x1,y1,t0,t1" moves in a line; "A:cx,cy,r,deg0,deg1,t0,t1" moves on an arc.
     * Tracks are separated by ';'. Pointers go down at t0 and up at t1.
     */
    private void tracks(String spec){
        if(spec==null||spec.isEmpty())return;
        String[] parts=spec.split(";");
        final int n=parts.length;
        final char[] kind=new char[n];final float[][] v=new float[n][];
        int end=0;
        for(int i=0;i<n;i++){
            String p=parts[i].trim();kind[i]=p.charAt(0);
            String[] f=p.substring(2).split(",");v[i]=new float[f.length];
            for(int k=0;k<f.length;k++)v[i][k]=Float.parseFloat(f[k]);
            end=Math.max(end,(int)v[i][f.length-1]);
        }
        final long down=SystemClock.uptimeMillis();
        boolean[] isDown=new boolean[n],ended=new boolean[n];
        for(int t=0;t<=end+8;t+=8){
            int clock=Math.min(t,end);
            for(int i=0;i<n;i++)if(!isDown[i]&&!ended[i]&&v[i][v[i].length-2]<=clock){isDown[i]=true;post(down,clock,n,kind,v,isDown,i,true);}
            post(down,clock,n,kind,v,isDown,-1,false);
            for(int i=0;i<n;i++)if(isDown[i]&&v[i][v[i].length-1]<=clock){post(down,clock,n,kind,v,isDown,i,false);isDown[i]=false;ended[i]=true;}
            if(t>=end)break;
        }
    }
    /** One MotionEvent for the pointers now down; changed>=0 makes it that pointer's down (or up). */
    private void post(long down,int clock,int n,char[] kind,float[][] v,boolean[] isDown,int changed,boolean goingDown){
        int count=0;for(int i=0;i<n;i++)if(isDown[i])count++;
        if(count==0)return;
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        int index=0,changedIndex=0;
        for(int i=0;i<n;i++){
            if(!isDown[i])continue;
            float[] a=v[i];float t0=a[a.length-2],t1=a[a.length-1];
            float f=t1>t0?Math.max(0,Math.min(1,(clock-t0)/(t1-t0))):1;
            float x,y;
            if(kind[i]=='A'){double deg=Math.toRadians(a[3]+(a[4]-a[3])*f);x=a[0]+(float)Math.cos(deg)*a[2];y=a[1]+(float)Math.sin(deg)*a[2];}
            else{x=a[0]+(a[2]-a[0])*f;y=a[1]+(a[3]-a[1])*f;}
            properties[index]=new MotionEvent.PointerProperties();properties[index].id=i;properties[index].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[index]=new MotionEvent.PointerCoords();coords[index].x=x;coords[index].y=y;coords[index].pressure=1;coords[index].size=.1f;
            if(i==changed)changedIndex=index;
            index++;
        }
        int action=MotionEvent.ACTION_MOVE;
        if(changed>=0){
            if(count==1)action=goingDown?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP;
            else action=(goingDown?MotionEvent.ACTION_POINTER_DOWN:MotionEvent.ACTION_POINTER_UP)|(changedIndex<<MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        final MotionEvent event=MotionEvent.obtain(down,down+clock,action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        handler.postAtTime(()->{activity.dispatchTouchEvent(event);event.recycle();},down+clock);
    }
    private void perform(Intent intent){
        int w=activity.getWindow().getDecorView().getWidth(),h=activity.getWindow().getDecorView().getHeight();
        final float x=intent.getFloatExtra("x",.6f)*w,y=intent.getFloatExtra("y",.5f)*h;
        final float dx=intent.getFloatExtra("dx",.12f)*w,dy=intent.getFloatExtra("dy",0)*h;
        String scenario=intent.getStringExtra("scenario");if(scenario==null)scenario="tap";
        if(scenario.equals("snapshot")){snapshot();return;}
        if(scenario.equals("tracks")){tracks(intent.getStringExtra("tracks"));return;}
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
