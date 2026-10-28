#include "AndroidRuntime.hpp"
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <string>
#include <vector>
#include <unistd.h>
#include "platform.h"
static std::string presentation = "vulkan";
static float renderScale = 0;
static int frameCap = 0;
extern "C" const char* KfxAndroidPresentationDriver(void) { return presentation.c_str(); }
extern "C" float KfxAndroidRenderScale(void) { return renderScale; }
extern "C" int KfxAndroidFrameCap(void) { return frameCap; }
extern "C" void KfxAndroidFramePresented(void) {
    static uint64_t started=0, frames=0, last=0;
    uint64_t now=SDL_GetTicksNS();
    if (frameCap > 0 && last > 0) {
        uint64_t target=last + 1000000000ULL/frameCap;
        if (now < target) SDL_DelayPrecise(target-now);
        now=SDL_GetTicksNS();
    }
    last=now;
    if (!started) started=now;
    frames++;
    if (now-started >= 5000000000ULL) {
        __android_log_print(ANDROID_LOG_INFO,"KeeperPerformance","present_fps=%.2f frames=%llu duration_ms=%llu backend=%s",frames*1e9/(now-started),static_cast<unsigned long long>(frames),static_cast<unsigned long long>((now-started)/1000000),presentation.c_str());
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
        else values.push_back(arg);
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
