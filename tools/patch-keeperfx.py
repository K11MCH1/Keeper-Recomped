"""Apply small, idempotent Android seams to the pinned KeeperFX source checkout."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/keeperfx/src"
# `--emit-seams out.json` records the edits in order instead of applying them; the
# launcher applies the same list to KeeperFX sources it downloads (on-phone builds).
SEAMS = None


def replace(name, old, new):
    if SEAMS is not None:
        SEAMS.append({"file": name, "old": old, "new": new})
        return
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
    # Campaign captions are drawn taller and lower than their desktop hit boxes.
    # Align touch rows with the caption centres, retaining one row per choice.
    replace("bflib_guibtns.c", '#include "config_sounds.h"', '#include "config_sounds.h"\n#ifdef KFX_ANDROID\n#include "frontmenu_select.h"\n#endif')
    replace("bflib_guibtns.c", '    TbScreenPos y = gbtn->pos_y;\n    if ( (pos_x >= x)', '    TbScreenPos y = gbtn->pos_y;\n#ifdef KFX_ANDROID\n    if (gbtn->draw_call == frontend_draw_campaign_select_button)\n        y = gbtn->scr_pos_y + (gbtn->height * 13 / 11 - gbtn->height) / 2;\n#endif\n    if ( (pos_x >= x)')
    # Touch zoom and rotation live in the local camera only; keep them when the
    # parchment map closes instead of restoring the synced pose (and the map jump's
    # yaw 0). Local-only: the synced yaw catches up through PckA_SetMapRotation.
    # (Claude, 2026-10-29)
    for name in ["front_input.c", "packets.c"]:
        include(name)
    # A null call leaves PC=0 and no backtrace; the return address names the caller.
    replace("bflib_crash.c", '        (void *)uctx->uc_mcontext.pc,\n        (void *)uctx->uc_mcontext.sp);\n', '        (void *)uctx->uc_mcontext.pc,\n        (void *)uctx->uc_mcontext.sp);\n#ifdef KFX_ANDROID\n    {\n        void *lr = (void *)uctx->uc_mcontext.regs[30];\n        Dl_info info;\n        if (dladdr(lr, &info) && info.dli_fbase != NULL)\n            LbErrorLog("Context LR=%p %s+0x%lx %s\\n", lr, info.dli_fname, (unsigned long)((char *)lr - (char *)info.dli_fbase), info.dli_sname ? info.dli_sname : "?");\n        else\n            LbErrorLog("Context LR=%p\\n", lr);\n    }\n#endif\n')
    replace("local_camera.c", '        pose.yaw[true] = 0;\n    }\n    sync_local_camera_pose(player, &pose);\n}', '        pose.yaw[true] = 0;\n    }\n#ifdef KFX_ANDROID\n    for (int i = 0; i < 2; i++) {\n        const struct Camera *dcam = &local_state.camera.destination[i ? CamIV_FrontView : CamIV_Isometric];\n        if (dcam->zoom > 0) pose.zoom[i] = dcam->zoom;\n        pose.yaw[i] = dcam->rotation_angle_x;\n    }\n#endif\n    sync_local_camera_pose(player, &pose);\n}')
    replace("packets.c", '        set_all_cameras_rotation(cams, 0);\n', '#ifndef KFX_ANDROID\n        set_all_cameras_rotation(cams, 0);\n#endif\n')
    # Possession on touch: the on-screen stick feeds the movement axes, a right-side
    # drag supplies look, and the cursor stays free so the side panel can be tapped.
    replace("front_input.c", '    float movement_y = get_game_key_axis_value(Gkey_MoveDown, ignore_mods) - get_game_key_axis_value(Gkey_MoveUp, ignore_mods);\n', '    float movement_y = get_game_key_axis_value(Gkey_MoveDown, ignore_mods) - get_game_key_axis_value(Gkey_MoveUp, ignore_mods);\n#ifdef KFX_ANDROID\n    float android_x = 0.0f, android_y = 0.0f;\n    KfxAndroidStickAxes(&android_x, &android_y);\n    movement_x = clamp(movement_x + android_x, -1.0f, 1.0f);\n    movement_y = clamp(movement_y + android_y, -1.0f, 1.0f);\n#endif\n')
    replace("front_input.c", '    long x = GetMouseX();\n    long y = GetMouseY();\n    struct Thing* thing = thing_get(get_local_user_state()->controlled_thing_idx);', '    long x = GetMouseX();\n    long y = GetMouseY();\n#ifdef KFX_ANDROID\n    x = MyScreenWidth >> 1;\n    y = MyScreenHeight >> 1;\n#endif\n    struct Thing* thing = thing_get(get_local_user_state()->controlled_thing_idx);')
    replace("front_input.c", '        long deltaX = x - centerX;\n        long deltaY = y - centerY;', '        long deltaX = x - centerX;\n        long deltaY = y - centerY;\n#ifdef KFX_ANDROID\n        float android_look_x = 0.0f, android_look_y = 0.0f;\n        KfxAndroidPossessionLook(&android_look_x, &android_look_y);\n        deltaX += (long)(android_look_x * centerX);\n        deltaY += (long)(android_look_y * centerY);\n#endif')
    # The runtime directory is absolute on Android, so prepare_file_path() paths are too;
    # the CD loader hook would prefix the install folder again (net map hand sprites).
    replace("frontend.cpp", "    if (input[0] != '*') {\n        snprintf(path_string", "#ifdef KFX_ANDROID\n    if (input[0] == '/') return input;\n#endif\n    if (input[0] != '*') {\n        snprintf(path_string")
    # The LAN lobby's chat line keeps text input on; start it without the soft keyboard
    # (a tap on the chat line shows it, see TouchBridge.cpp).
    for name in ["frontend.cpp", "frontmenu_net.c"]:
        include(name)
    replace("frontend.cpp", "          turn_on_menu(GMnu_FENET_START);\n          LbStartTextInput();", "          turn_on_menu(GMnu_FENET_START);\n#ifdef KFX_ANDROID\n          KfxAndroidStartQuietTextInput();\n#else\n          LbStartTextInput();\n#endif")
    # libcurl finds no CA certificates on Android, so online matchmaking (wss://) failed the
    # TLS check; the launcher exports the phone's trusted CAs and both handles use them.
    include("net_matchmaking.c")
    replace("net_matchmaking.c", "    curl_easy_setopt(handle, CURLOPT_URL, matchmaking_ip_url);", "#ifdef KFX_ANDROID\n    if (KfxAndroidCaBundle()) curl_easy_setopt(handle, CURLOPT_CAINFO, KfxAndroidCaBundle());\n#endif\n    curl_easy_setopt(handle, CURLOPT_URL, matchmaking_ip_url);")
    replace("net_matchmaking.c", "        curl_easy_setopt(curl_handle, CURLOPT_URL, matchmaking_ws_url);", "#ifdef KFX_ANDROID\n        if (KfxAndroidCaBundle()) curl_easy_setopt(curl_handle, CURLOPT_CAINFO, KfxAndroidCaBundle());\n#endif\n        curl_easy_setopt(curl_handle, CURLOPT_URL, matchmaking_ws_url);")
    replace("frontmenu_net.c", "    if (!LbIsTextInputActive()) {\n        LbStartTextInput();\n    }", "    if (!LbIsTextInputActive()) {\n#ifdef KFX_ANDROID\n        KfxAndroidStartQuietTextInput();\n#else\n        LbStartTextInput();\n#endif\n    }")
    print("Applied Android input, camera, entry, presentation and scaling seams")


if __name__ == "__main__":
    import json
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == "--emit-seams":
        SEAMS = []
        main()
        Path(sys.argv[2]).write_text(json.dumps({"base": "src", "seams": SEAMS}, indent=1), encoding="utf-8")
    else:
        main()
