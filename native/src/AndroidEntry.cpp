#include "AndroidRuntime.hpp"
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <android/native_window.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <vector>
#include <unistd.h>
#include "platform.h"
#include "bflib_basics.h"
#include "keeperfx.hpp"
static std::string presentation = "vulkan";
static float renderScale = 0;
static int frameCap = 0;
static bool vsync = true, showFPS = false;
static bool inputDiagnostics = false;
static bool cheatsEnabled = false;
static double measuredFPS = 0;
extern "C" const char* KfxAndroidPresentationDriver(void) { return presentation.c_str(); }
extern "C" float KfxAndroidRenderScale(void) { return renderScale; }
extern "C" int KfxAndroidFrameCap(void) { return frameCap; }
extern "C" int KfxAndroidVsync(void) { return vsync ? 1 : 0; }
extern "C" int KfxAndroidInputDiagnostics(void) { return inputDiagnostics ? 1 : 0; }
extern "C" int KfxAndroidCheatsEnabled(void) { return cheatsEnabled ? 1 : 0; }
extern "C" void KfxAndroidConfigureWindow(SDL_Window* window) {
    float rate=frameCap>0?static_cast<float>(frameCap):60.0f;
    SDL_DisplayID display=SDL_GetDisplayForWindow(window);
    if (!frameCap) {
        int count=0;auto modes=SDL_GetFullscreenDisplayModes(display,&count);
        for(int i=0;modes && i<count;i++)if(modes[i]->refresh_rate<=165.5f)rate=std::max(rate,modes[i]->refresh_rate);
        SDL_free(modes);
    }
    auto* native=static_cast<ANativeWindow*>(SDL_GetPointerProperty(SDL_GetWindowProperties(window),SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER,nullptr));
    if(native) {
        int result=ANativeWindow_setFrameRateWithChangeStrategy(native,rate,ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
        LbSyncLog("AndroidDisplay requested_refresh=%.2f result=%d\n",rate,result);
    }
}
extern "C" void KfxAndroidDrawOverlay(SDL_Renderer* renderer) {
    KfxAndroidDrawTouchOverlay(renderer);
    if (!showFPS) return;
    float sx=1,sy=1;Uint8 r,g,b,a;
    SDL_GetRenderScale(renderer,&sx,&sy);SDL_GetRenderDrawColor(renderer,&r,&g,&b,&a);
    SDL_SetRenderScale(renderer,3,3);
    SDL_FRect background{3,3,94,14};SDL_SetRenderDrawColor(renderer,0,0,0,255);SDL_RenderFillRect(renderer,&background);
    SDL_SetRenderDrawColor(renderer,255,230,150,255);
    char text[32];std::snprintf(text,sizeof(text),"FPS %.1f",measuredFPS);
    SDL_RenderDebugText(renderer,6,6,text);
    SDL_SetRenderScale(renderer,sx,sy);SDL_SetRenderDrawColor(renderer,r,g,b,a);
}
extern "C" void KfxAndroidFramePresented(void) {
    static uint64_t started=0, frames=0, last=0, overlayStart=0, overlayFrames=0;
    static float detectedRefresh=0;
    uint64_t now=SDL_GetTicksNS();
    int cap=frameCap;
    if (!cap) {
        const SDL_DisplayMode* mode=SDL_GetCurrentDisplayMode(SDL_GetPrimaryDisplay());
        cap=mode?std::clamp(static_cast<int>(std::lround(mode->refresh_rate)),60,165):144;
    }
    if (!vsync && last > 0) {
        uint64_t target=last + 1000000000ULL/cap;
        if (now < target) SDL_DelayPrecise(target-now);
        now=SDL_GetTicksNS();
    }
    last=now;
    if (!overlayStart) overlayStart=now;
    overlayFrames++;
    if (now-overlayStart>=1000000000ULL) {
        measuredFPS=overlayFrames*1e9/(now-overlayStart);overlayStart=now;overlayFrames=0;
        const SDL_DisplayMode* mode=SDL_GetCurrentDisplayMode(SDL_GetPrimaryDisplay());
        if(mode && std::fabs(mode->refresh_rate-detectedRefresh)>1) { detectedRefresh=mode->refresh_rate;redetect_screen_refresh_rate_for_draw(); }
    }
    if (!started) started=now;
    frames++;
    if (now-started >= 5000000000ULL) {
        __android_log_print(ANDROID_LOG_INFO,"KeeperPerformance","present_fps=%.2f frames=%llu duration_ms=%llu backend=%s",frames*1e9/(now-started),static_cast<unsigned long long>(frames),static_cast<unsigned long long>((now-started)/1000000),presentation.c_str());
        LbSyncLog("AndroidPerformance present_fps=%.2f frames=%llu duration_ms=%llu backend=%s\n",frames*1e9/(now-started),static_cast<unsigned long long>(frames),static_cast<unsigned long long>((now-started)/1000000),presentation.c_str());
        started=now;frames=0;
    }
}

extern "C" SDL_DECLSPEC int SDL_main(int argc, char** argv) {
    std::string root, profile = "keeperfx";
    float slop = 24, pinch = .06f, rotate = 12;
    std::vector<std::string> values{"keeperfx"};
    for (int i = 1; i < argc; i++) {
        std::string arg = argv[i];
        auto value = [&](const char* prefix) { return arg.substr(std::string(prefix).size()); };
        if (arg.starts_with("--game-dir=")) root = value("--game-dir=");
        else if (arg.starts_with("--profile=")) profile = value("--profile=");
        else if (arg.starts_with("--renderer=")) presentation = value("--renderer=") == "gles" ? "opengles2" : "vulkan";
        else if (arg.starts_with("--fps-cap=")) frameCap = std::clamp(std::atoi(value("--fps-cap=").c_str()), 0, 165);
        else if (arg.starts_with("--render-scale=")) renderScale = std::clamp(std::strtof(value("--render-scale=").c_str(), nullptr), 0.0f, 1.0f);
        else if (arg.starts_with("--touch-slop=")) slop = std::clamp(std::strtof(value("--touch-slop=").c_str(), nullptr), 4.0f, 160.0f);
        else if (arg.starts_with("--rotate-deadzone=")) rotate = std::clamp(std::strtof(value("--rotate-deadzone=").c_str(), nullptr), 5.0f, 45.0f);
        else if (arg.starts_with("--pinch-deadzone=")) pinch = std::clamp(std::strtof(value("--pinch-deadzone=").c_str(), nullptr), .02f, .25f);
        else if (arg.starts_with("--vsync=")) vsync=value("--vsync=")!="0";
        else if (arg.starts_with("--show-fps=")) showFPS=value("--show-fps=")=="1";
        else if (arg.starts_with("--input-diagnostics=")) inputDiagnostics=value("--input-diagnostics=")=="1";
        else { if(arg=="-alex") cheatsEnabled=true; values.push_back(arg); }
    }
    if (root.empty() || root.front() != '/' || chdir(root.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "KeeperAndroid", "Prepared game directory is unavailable");
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Keeper Recomped", "Prepare the game folder in the launcher before playing.", nullptr);
        return 2;
    }
    // argv[0] controls the engine's runtime-directory discovery. Use the real cwd.
    values[0] = root + "/keeperfx";
    SDL_SetHint(SDL_HINT_RENDER_DRIVER, presentation.c_str());
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_RELATIVE_MODE_CENTER, "0");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    KfxAndroidConfigureTouch(slop, pinch, rotate);
    std::vector<char*> args;
    for (auto& v : values) args.push_back(v.data());
    args.push_back(nullptr);
    __android_log_print(ANDROID_LOG_INFO, "KeeperAndroid", "Starting ARM64 KeeperFX: profile=%s presentation=%s cap=%d scale=%.2f source=da038f03a", profile.c_str(), presentation.c_str(), frameCap, renderScale);
    return kfxmain(static_cast<int>(values.size()), args.data());
}
