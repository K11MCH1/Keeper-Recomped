package org.keeperrecomped.game;

import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import org.libsdl.app.SDLActivity;
import java.util.ArrayList;
import java.util.Collections;

/** SDL owns the native surface and lifecycle; the launcher owns preparation. */
public final class GameActivity extends SDLActivity {
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
}
