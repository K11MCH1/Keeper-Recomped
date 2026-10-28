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
    # Desktop GL is retained for source compatibility; Android uses SDL's real
    # Vulkan/GLES presenter over the existing software game drawing path.
    replace("kfx/renderer/RendererManager.cpp", "    return (requested == RENDERER_AUTO) ? RENDERER_SOFTWARE : requested;", "#ifdef KFX_ANDROID\n    return RENDERER_SOFTWARE;\n#else\n    return (requested == RENDERER_AUTO) ? RENDERER_SOFTWARE : requested;\n#endif")
    # Expose SDL_main from the Android entry bridge, retaining desktop main.
    replace("kfx/platform/PlatformLinux.cpp", 'extern "C" int main(int argc, char *argv[])\n{\n    return kfxmain(argc, argv);\n}', '#ifndef KFX_ANDROID\nextern "C" int main(int argc, char *argv[])\n{\n    return kfxmain(argc, argv);\n}\n#endif')
    replace("kfx/platform/PlatformLinux.cpp", '#include <fnmatch.h>', '#include <fnmatch.h>\n#ifndef FNM_FILE_NAME\n#define FNM_FILE_NAME FNM_PATHNAME\n#endif')
    # Preserve native screen dimensions for the SDL window; reduce only the
    # indexed draw surface to an aspect-correct workload the CPU can sustain.
    replace("bflib_video.c", 'TbScreenModeInfo* mdinfo = LbScreenGetModeInfo(mode); // The desired mode has already been checked', 'TbScreenModeInfo* mdinfo = LbScreenGetModeInfo(mode); // The desired mode has already been checked\n#ifdef KFX_ANDROID\n    float android_scale = KfxAndroidRenderScale();\n    if (android_scale <= 0.0f) android_scale = mdinfo->Width > 1280 ? 1280.0f / mdinfo->Width : 1.0f;\n    width = (TbScreenCoord)(mdinfo->Width * android_scale);\n    height = (TbScreenCoord)(mdinfo->Height * android_scale);\n    if (width < 640) { height = (TbScreenCoord)(height * 640.0f / width); width = 640; }\n    width = (width + 3) & ~3;\n#endif')
    replace("bflib_video.c", '    struct SDL_Surface *draw_surface = SwDisplaySurfaceCreate(mdinfo->Width, mdinfo->Height);', '#ifdef KFX_ANDROID\n    TbScreenModeInfo android_draw_mode = *mdinfo;\n    android_draw_mode.Width = width;\n    android_draw_mode.Height = height;\n    mdinfo = &android_draw_mode;\n#endif\n    struct SDL_Surface *draw_surface = SwDisplaySurfaceCreate(mdinfo->Width, mdinfo->Height);')
    print("Applied Android input, camera, entry, presentation and scaling seams")


if __name__ == "__main__":
    main()
