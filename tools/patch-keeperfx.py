"""Apply small, idempotent Android seams to the pinned KeeperFX source checkout."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/keeperfx/src"


def replace(name, old, new):
    path = SOURCE / name
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one source anchor in {name}: {old[:70]}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")


def include(name):
    replace(name, '#include "pre_inc.h"', '#include "pre_inc.h"\n#ifdef KFX_ANDROID\n#include "AndroidRuntime.hpp"\n#endif')


def main():
    for name in ["bflib_inputctrl.cpp", "local_camera.c", "kfx/renderer/RendererSoftware.cpp", "bflib_video.c"]:
        include(name)
    replace("bflib_inputctrl.cpp", "    switch (ev->type)\n", "#ifdef KFX_ANDROID\n    if (KfxAndroidTouchEvent(ev)) return;\n#endif\n    switch (ev->type)\n")
    replace("bflib_inputctrl.cpp", "        process_event(&ev);\n    }", "        process_event(&ev);\n    }\n#ifdef KFX_ANDROID\n    KfxAndroidTouchTick();\n#endif")
    replace("local_camera.c", "void update_local_cameras(void)\n{", "void update_local_cameras(void)\n{\n#ifdef KFX_ANDROID\n    KfxAndroidApplyCamera();\n#endif")
    replace("kfx/renderer/RendererSoftware.cpp", "m_renderer = SDL_CreateRenderer(window, NULL);", "#ifdef KFX_ANDROID\n        m_renderer = SDL_CreateRenderer(window, KfxAndroidPresentationDriver());\n#else\n        m_renderer = SDL_CreateRenderer(window, NULL);\n#endif")
    replace("kfx/renderer/RendererSoftware.cpp", "    SDL_RenderPresent(m_renderer);", "    SDL_RenderPresent(m_renderer);\n#ifdef KFX_ANDROID\n    KfxAndroidFramePresented();\n#endif")
    replace("kfx/renderer/RendererSoftware.cpp", "    SDL_RenderPresent(m_renderer);\n#ifdef KFX_ANDROID", "#ifdef KFX_ANDROID\n    KfxAndroidDrawOverlay(m_renderer);\n#endif\n    SDL_RenderPresent(m_renderer);\n#ifdef KFX_ANDROID")
    replace("kfx/renderer/RendererSoftware.cpp", "    const int want_vsync = vsync_enabled ? 1 : 0;", "#ifdef KFX_ANDROID\n    const int want_vsync = KfxAndroidVsync();\n#else\n    const int want_vsync = vsync_enabled ? 1 : 0;\n#endif")
    replace("kfx/renderer/RendererSoftware.cpp", "bool RendererSoftware::ensure_present_target()\n{", "bool RendererSoftware::ensure_present_target()\n{\n#ifdef KFX_ANDROID\n    static Uint32 observed_reset = 0;\n    Uint32 reset = KfxAndroidPresentationGeneration();\n    if (reset != observed_reset) { destroy_present_target(); observed_reset = reset; }\n#endif")
    replace("kfx/renderer/RendererSoftware.cpp", '        m_renderer = SDL_CreateRenderer(window, KfxAndroidPresentationDriver());', '        KfxAndroidConfigureWindow(window);\n        m_renderer = SDL_CreateRenderer(window, KfxAndroidPresentationDriver());')
    replace("kfx/renderer/RendererSoftware.cpp", '        ERRORLOG("Present texture lock failed: %s", SDL_GetError());\n        return;', '        ERRORLOG("Present texture lock failed: %s", SDL_GetError());\n#ifdef KFX_ANDROID\n        SDL_DestroyTexture(m_texture); m_texture = nullptr;\n#endif\n        return;')
    # Desktop GL is retained for source compatibility; Android uses SDL's real
    # Vulkan/GLES presenter over the existing software game drawing path.
    replace("kfx/renderer/RendererManager.cpp", "    return (requested == RENDERER_AUTO) ? RENDERER_SOFTWARE : requested;", "#ifdef KFX_ANDROID\n    return RENDERER_SOFTWARE;\n#else\n    return (requested == RENDERER_AUTO) ? RENDERER_SOFTWARE : requested;\n#endif")
    # Expose SDL_main from the Android entry bridge, retaining desktop main.
    replace("kfx/platform/PlatformLinux.cpp", 'extern "C" int main(int argc, char *argv[])\n{\n    return kfxmain(argc, argv);\n}', '#ifndef KFX_ANDROID\nextern "C" int main(int argc, char *argv[])\n{\n    return kfxmain(argc, argv);\n}\n#endif')
    replace("kfx/platform/PlatformLinux.cpp", '#include <fnmatch.h>', '#include <fnmatch.h>\n#ifndef FNM_FILE_NAME\n#define FNM_FILE_NAME FNM_PATHNAME\n#endif')
    # Lua's own filesystem loader bypasses KeeperFX's case-insensitive file
    # abstraction. KeeperFX archives keep mixed-case names (fxdata/lua/classes/
    # Pos3d.lua) while imported DK files are lowercased, so try the exact module
    # name first and fall back to lowercase. (Claude, 2026-10-29: the earlier
    # lowercase-only loader broke level start on launcher-prepared trees.)
    replace("lua_base.c", '    setLuaPath(Lvl_script);', '    setLuaPath(Lvl_script);\n#ifdef KFX_ANDROID\n    if (!CheckLua(Lvl_script, luaL_dostring(Lvl_script, "local o=package.loaders[2]; package.loaders[2]=function(n) local r=o(n); if type(r)==\'function\' then return r end; local l=o(string.lower(n)); if type(l)==\'function\' then return l end; return r end"), "android_module_case")) { close_lua_script(); return false; }\n#endif')
    # load_configuration() resets keeper_runtime_directory to "." and every data
    # path is built from it; SDL3 on Android opens relative paths as APK assets,
    # so music and streamed speech failed. Keep it absolute (the cwd is the game root).
    # SDL_mixer's dr_mp3 (enabled for MP3 campaign briefings, native/ports/sdl3-mixer)
    # and KeeperFX's own copy are both linked statically; keep KeeperFX's private.
    replace("bflib_sndlib.cpp", '#define DR_MP3_IMPLEMENTATION\n#include "../deps/dr_mp3.h"', '#define DR_MP3_IMPLEMENTATION\n#ifdef KFX_ANDROID\n#define DRMP3_API static\n#endif\n#include "../deps/dr_mp3.h"')
    replace("config_keeperfx.c", '#include <stdarg.h>','#include <stdarg.h>\n#ifdef KFX_ANDROID\n#include <unistd.h>\n#endif')
    replace("config_keeperfx.c", '  strcpy(keeper_runtime_directory,".");\n  // Config file variables', '#ifdef KFX_ANDROID\n  if (getcwd(keeper_runtime_directory, sizeof(keeper_runtime_directory)) == NULL)\n      strcpy(keeper_runtime_directory,".");\n#else\n  strcpy(keeper_runtime_directory,".");\n#endif\n  // Config file variables')
    replace("main_game.c", '    luascript_loaded = open_lua_script(get_selected_level_number());', '    luascript_loaded = open_lua_script(get_selected_level_number());\n#ifdef KFX_ANDROID\n    if (!luascript_loaded) { ERRORLOG("Level initialization stopped: global Lua runtime failed"); return false; }\n#endif')
    replace("vidmode.c", 'TbBool update_screen_mode_data(long width, long height)\n{', 'TbBool update_screen_mode_data(long width, long height)\n{\n#ifdef KFX_ANDROID\n  /* SDL window pixels and the indexed draw surface are separate sizes. */\n  width = lbDisplay.PhysicalScreenWidth;\n  height = lbDisplay.PhysicalScreenHeight;\n#endif')
    # Preserve native screen dimensions for the SDL window; reduce only the
    # indexed draw surface to an aspect-correct workload the CPU can sustain.
    replace("bflib_video.c", 'TbScreenModeInfo* mdinfo = LbScreenGetModeInfo(mode); // The desired mode has already been checked', 'TbScreenModeInfo* mdinfo = LbScreenGetModeInfo(mode); // The desired mode has already been checked\n#ifdef KFX_ANDROID\n    float android_scale = KfxAndroidRenderScale();\n    if (android_scale <= 0.0f) android_scale = mdinfo->Width > 1280 ? 1280.0f / mdinfo->Width : 1.0f;\n    width = (TbScreenCoord)(mdinfo->Width * android_scale);\n    height = (TbScreenCoord)(mdinfo->Height * android_scale);\n    if (width < 640) { height = (TbScreenCoord)(height * 640.0f / width); width = 640; }\n    width = (width + 3) & ~3;\n#endif')
    replace("bflib_video.c", '    struct SDL_Surface *draw_surface = SwDisplaySurfaceCreate(mdinfo->Width, mdinfo->Height);', '#ifdef KFX_ANDROID\n    TbScreenModeInfo android_draw_mode = *mdinfo;\n    android_draw_mode.Width = width;\n    android_draw_mode.Height = height;\n    mdinfo = &android_draw_mode;\n#endif\n    struct SDL_Surface *draw_surface = SwDisplaySurfaceCreate(mdinfo->Width, mdinfo->Height);')
    print("Applied Android input, camera, entry, presentation and scaling seams")


if __name__ == "__main__":
    main()
