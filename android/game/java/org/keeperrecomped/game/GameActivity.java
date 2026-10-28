package org.keeperrecomped.game;

import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.RelativeLayout;
import org.libsdl.app.SDLActivity;
import java.util.ArrayList;
import java.util.Collections;

/** SDL owns the native surface and lifecycle; the launcher owns preparation. */
public final class GameActivity extends SDLActivity {
    private AutoCloseable debugProbe;
    private static native void requestMenu(int action);
    @Override protected String[] getLibraries() { return new String[]{"SDL3", "keeperfx"}; }
    @Override protected String getMainSharedObject() { return getApplicationInfo().nativeLibraryDir + "/libkeeperfx.so"; }
    @Override protected String getMainFunction() { return "SDL_main"; }
    @Override protected String[] getArguments() {
        ArrayList<String> args = new ArrayList<>();
        args.add("--game-dir=" + getIntent().getStringExtra("game_root"));
        args.add("--profile=" + getIntent().getStringExtra("profile"));
        String renderer=getIntent().getStringExtra("renderer");
        args.add("--renderer=" + ("gles".equals(renderer) ? "gles" : "vulkan"));
        args.add("--fps-cap=" + getIntent().getIntExtra("fps_cap", 0));
        args.add("--render-scale=" + getIntent().getFloatExtra("render_scale", 0));
        args.add("--touch-slop=" + ViewConfiguration.get(this).getScaledTouchSlop());
        args.add("--rotate-deadzone=" + getIntent().getFloatExtra("touch_rotate_deadzone_deg", 12));
        args.add("--pinch-deadzone=" + getIntent().getFloatExtra("touch_pinch_deadzone", .06f));
        args.add("--vsync=" + (getIntent().getBooleanExtra("vsync",true) ? "1" : "0"));
        args.add("--show-fps=" + (getIntent().getBooleanExtra("show_fps",false) ? "1" : "0"));
        args.add("--input-diagnostics=" + (getIntent().getBooleanExtra("input_diagnostics",false) ? "1" : "0"));
        String[] extra = getIntent().getStringArrayExtra("args");
        if (extra != null) Collections.addAll(args, extra);
        return args.toArray(new String[0]);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("debug_show_when_locked", false)) {
            // Display only this test activity over keyguard. This does not unlock
            // the device or change lock-screen settings, and is debug opt-in only.
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController insets=getWindow().getInsetsController();
        if(insets!=null){insets.hide(WindowInsets.Type.systemBars());insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}
        WindowManager.LayoutParams screenParams=getWindow().getAttributes();
        screenParams.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(screenParams);
        String[] engineArgs=getIntent().getStringArrayExtra("args");
        if(engineArgs!=null && java.util.Arrays.asList(engineArgs).contains("-alex")) {
            Button cheats=new Button(this);cheats.setText("Cheats");cheats.setTextSize(12);cheats.setAlpha(.65f);
            cheats.setOnClickListener(v->requestMenu(2));
            cheats.setOnLongClickListener(v->{requestMenu(3);return true;});
            float density=getResources().getDisplayMetrics().density;
            RelativeLayout.LayoutParams params=new RelativeLayout.LayoutParams((int)(88*density),(int)(40*density));
            params.addRule(RelativeLayout.ALIGN_PARENT_END);params.topMargin=(int)(8*density);params.rightMargin=(int)(12*density);
            mLayout.addView(cheats,params);
        }
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("enable_touch_probe",false)) {
            try { debugProbe=(AutoCloseable)Class.forName("org.keeperrecomped.game.TouchProbe").getMethod("install",android.app.Activity.class).invoke(null,this); }
            catch(ReflectiveOperationException ignored) { }
        }
        // Match the real panel. Rendering targets may cap lower, never change simulation TPS.
        Display display = getDisplay();
        int requested = getIntent().getIntExtra("fps_cap", 0);
        if (display != null) {
            Display.Mode current=display.getMode(), best=current;
            float ceiling=requested > 0 ? Math.min(165,requested) : 165;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth()==current.getPhysicalWidth() && mode.getPhysicalHeight()==current.getPhysicalHeight()
                    && mode.getRefreshRate() <= ceiling+.5f && (best.getRefreshRate()>ceiling+.5f || mode.getRefreshRate()>best.getRefreshRate())) best=mode;
            }
            WindowManager.LayoutParams params=getWindow().getAttributes();
            params.preferredDisplayModeId=best.getModeId(); params.preferredRefreshRate=best.getRefreshRate();
            getWindow().setAttributes(params);
        }
    }
    @Override protected void onDestroy() {
        if(debugProbe!=null)try{debugProbe.close();}catch(Exception ignored){}
        boolean finishing=isFinishing();
        super.onDestroy();
        // The launcher places this activity in :game. A fresh process is required
        // for the legacy engine globals on the next Play action.
        if(finishing)android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public void onBackPressed(){requestMenu(1);}
}
