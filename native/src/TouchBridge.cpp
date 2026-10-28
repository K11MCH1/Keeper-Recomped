#include "AndroidRuntime.hpp"
#include "TouchGestures.hpp"
#include "bflib_mouse.h"
#include "bflib_mshandler.hpp"
#include "bflib_video.h"
#include "kfx/platform/WindowSystemSDL.h"
#include "kfx/renderer/RendererManager.h"
#include "keeperfx.hpp"
#include "local_camera.h"
#include "player_data.h"
#include "engine_camera.h"
#include "dungeon_data.h"
#include "thing_data.h"
#include "bflib_basics.h"
#include "engine_render.h"
#include "thing_list.h"
#include "frontend.h"
#include "gui_frontbtns.h"
#include "bflib_inputctrl.h"
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <cmath>

using namespace keeper::touch;
static Gestures gestures;
static float panX = 0, panY = 0, zoomDelta = 0, rotationDelta = 0;
static uint64_t lastDrain = 0;
static Uint32 presentationGeneration = 0;
static uint64_t inputSnapshotAt = 0;
static int inputThing = 0;
static std::atomic<int> menuRequest{0};
static std::atomic<bool> diagnosticSnapshot{false};
extern "C" JNIEXPORT void JNICALL Java_org_keeperrecomped_game_GameActivity_requestMenu(JNIEnv*,jclass,jint action) { menuRequest.store(action); if (KfxAndroidInputDiagnostics()) LbSyncLog("AndroidMenu request=%d\n", static_cast<int>(action)); }
extern "C" JNIEXPORT void JNICALL Java_org_keeperrecomped_game_TouchProbe_snapshot(JNIEnv*,jclass) { diagnosticSnapshot.store(true); }
extern "C" Uint32 KfxAndroidPresentationGeneration(void) { return presentationGeneration; }
// Android Back behaves exactly like Escape (in-game Options, frontend "back"): inject the
// key so KeeperFX's own Escape handling runs; release it a few frames later so the
// per-frame key latch sees it held. (Claude, 2026-10-29)
static int escapeReleaseTicks = 0;
static void pushEscape(bool down) {
    SDL_Event ev; SDL_zero(ev);
    ev.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
    ev.key.timestamp = SDL_GetTicksNS();
    ev.key.windowID = SDL_GetWindowID(GetSDLWindowSystem()->GetSDLWindow());
    ev.key.scancode = SDL_SCANCODE_ESCAPE;
    ev.key.key = SDLK_ESCAPE;
    ev.key.down = down;
    bool pushed = SDL_PushEvent(&ev);
    if (KfxAndroidInputDiagnostics()) LbSyncLog("AndroidMenu escape %s pushed=%d\n", down ? "down" : "up", static_cast<int>(pushed));
}

extern "C" void KfxAndroidConfigureTouch(float slop, float pinch, float rotate) {
    gestures.configure({slop, pinch, rotate, 450});
}
static SDL_FPoint gamePosition(float x, float y) {
    int ww = 0, wh = 0;
    GetSDLWindowSystem()->GetWindowSize(&ww, &wh);
    int rx=0, ry=0, rw=ww, rh=wh;
    GetSDLWindowSystem()->GetPresentRect(&rx, &ry, &rw, &rh);
    int dw=ww,dh=wh;
    SDL_GetWindowSizeInPixels(GetSDLWindowSystem()->GetSDLWindow(),&dw,&dh);
    float sx=ww>0?static_cast<float>(dw)/ww:1, sy=wh>0?static_cast<float>(dh)/wh:1;
    float gx = rw > 0 ? (x*sx-rx)*RendererPhysicalWidth()/rw : x;
    float gy = rh > 0 ? (y*sy-ry)*RendererPhysicalHeight()/rh : y;
    return {gx,gy};
}
static void position(float x, float y) {
    auto point=gamePosition(x,y);
    pointerHandler.SetMousePosition(std::clamp(static_cast<int>(point.x), 0, std::max(0, static_cast<int>(RendererPhysicalWidth())-1)),
                                    std::clamp(static_cast<int>(point.y), 0, std::max(0, static_cast<int>(RendererPhysicalHeight())-1)));
}
static void button(unsigned action) { TbPoint delta{0,0}; mouseControl(action, &delta); }
static void drain() {
    for (const auto& a : gestures.take()) {
        position(a.x,a.y);
        if (a.kind!=Kind::Hover && KfxAndroidInputDiagnostics()) {
            LbSyncLog("AndroidInput action=%d x=%.1f y=%.1f dx=%.4f dy=%.4f cursor=%ld,%ld target=%d\n",static_cast<int>(a.kind),a.x,a.y,a.dx,a.dy,static_cast<long>(lbDisplay.MMouseX),static_cast<long>(lbDisplay.MMouseY),local_state.local_thing_under_hand);
            inputSnapshotAt=SDL_GetTicks()+350;
            inputThing=local_state.local_thing_under_hand;
        }
        switch (a.kind) {
        case Kind::LeftClick: button(MActn_LBUTTONDOWN); button(MActn_LBUTTONUP); break;
        case Kind::RightClick: button(MActn_RBUTTONDOWN); button(MActn_RBUTTONUP); break;
        case Kind::LeftDown: button(MActn_LBUTTONDOWN); break;
        case Kind::LeftUp: button(MActn_LBUTTONUP); break;
        case Kind::RightDown: button(MActn_RBUTTONDOWN); break;
        case Kind::RightUp: button(MActn_RBUTTONUP); break;
        case Kind::Pan: panX -= a.dx; panY -= a.dy; break;
        case Kind::Zoom: zoomDelta += a.dx; break;
        case Kind::Rotate: rotationDelta += a.dx; break;
        default: break;
        }
    }
}
extern "C" int KfxAndroidTouchEvent(const SDL_Event* e) {
    if (e->type==SDL_EVENT_RENDER_DEVICE_RESET || e->type==SDL_EVENT_RENDER_TARGETS_RESET || e->type==SDL_EVENT_RENDER_DEVICE_LOST) {
        presentationGeneration++;
        return 0;
    }
    if (e->type == SDL_EVENT_WINDOW_FOCUS_LOST || e->type == SDL_EVENT_WILL_ENTER_BACKGROUND || e->type == SDL_EVENT_FINGER_CANCELED) {
        gestures.cancel(); drain(); panX=panY=zoomDelta=rotationDelta=0; return 0;
    }
    if ((e->type == SDL_EVENT_KEY_DOWN || e->type == SDL_EVENT_KEY_UP) && KfxAndroidInputDiagnostics())
        LbSyncLog("AndroidKey %s scancode=%d key=0x%x menu_active=%d show_gui=%d\n", e->type == SDL_EVENT_KEY_DOWN ? "down" : "up",
                  static_cast<int>(e->key.scancode), static_cast<unsigned>(e->key.key), static_cast<int>(a_menu_window_is_active()), static_cast<int>(local_state.show_gui));
    if (e->type != SDL_EVENT_FINGER_DOWN && e->type != SDL_EVENT_FINGER_UP && e->type != SDL_EVENT_FINGER_MOTION) return 0;
    int w=0,h=0; GetSDLWindowSystem()->GetWindowSize(&w,&h);
    float x=e->tfinger.x*w, y=e->tfinger.y*h;
    if (e->type == SDL_EVENT_FINGER_DOWN) {
        // Menus and the sidebar retain normal mouse dragging; world drags pan.
        const auto* player = get_my_player();
        bool world = player && (get_local_view_type(player) == PVT_DungeonTop || get_local_view_type(player) == PVT_MapScreen);
        auto point=gamePosition(x,y);
        gestures.setPanEnabled(world && point.x>=local_state.engine_window_x && point.x<local_state.engine_window_x+local_state.engine_window_width
            && point.y>=local_state.engine_window_y && point.y<local_state.engine_window_y+local_state.engine_window_height);
        gestures.down(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    } else if (e->type == SDL_EVENT_FINGER_UP) gestures.up(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    else gestures.move(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    drain(); return 1;
}
extern "C" void KfxAndroidTouchTick(void) {
    gestures.tick(SDL_GetTicks());drain();
    if (diagnosticSnapshot.exchange(false) && KfxAndroidInputDiagnostics()) { inputSnapshotAt=SDL_GetTicks();inputThing=local_state.local_thing_under_hand; }
    if (escapeReleaseTicks > 0 && --escapeReleaseTicks == 0) pushEscape(false);
    int request=menuRequest.exchange(0);
    if (request==1) {
        if (escapeReleaseTicks == 0) { pushEscape(true); escapeReleaseTicks = 3; }
    } else if (request && local_state.camera.ready && KfxAndroidCheatsEnabled()) {
        if(request==3) toggle_instance_cheat_menu(); else toggle_main_cheat_menu();
    }
    if (inputSnapshotAt && SDL_GetTicks()>=inputSnapshotAt && local_state.camera.ready) {
        inputSnapshotAt=0;
        auto* player=get_my_player();
        const auto* dungeon=get_players_dungeon(player);
        const auto* camera=get_local_active_camera(player);
        const auto* thing=thing_get(inputThing);
        LbSyncLog("AndroidInput snapshot hand=%d thing=%d health=%ld camera=%ld,%ld zoom=%d yaw=%d\n",dungeon_invalid(dungeon)?-1:dungeon->num_things_in_hand,inputThing,thing_is_invalid(thing)?-1L:static_cast<long>(thing->health),static_cast<long>(camera->mappos.x.val),static_cast<long>(camera->mappos.y.val),camera->zoom,camera->rotation_angle_x);
        int id=get_thing_class_list_head(TCls_Creature);
        for(int count=0;id>0 && count<16;count++) {
            auto* creature=thing_get(id);if(thing_is_invalid(creature))break;
            auto pos=creature->mappos;pos.z.val+=creature->clipbox_size_z/2;
            int32_t sx=0,sy=0;
            if (map_to_screen(&pos,&sx,&sy)) LbSyncLog("AndroidCreature id=%d owner=%d model=%d screen=%d,%d norm=%.4f,%.4f health=%ld\n",id,creature->owner,creature->model,sx,sy,sx/static_cast<double>(RendererPhysicalWidth()),sy/static_cast<double>(RendererPhysicalHeight()),static_cast<long>(creature->health));
            id=creature->next_of_class;
        }
    }
}
extern "C" void KfxAndroidCameraInputs(float* x, float* y, float* z, float* r) {
    uint64_t now=SDL_GetTicks();
    float dt=lastDrain ? std::clamp((now-lastDrain)/1000.0f,.001f,.1f) : 1.f/60.f;
    lastDrain=now;
    // Convert drag displacement to a bounded camera velocity, independent of FPS.
    int w=0,h=0; GetSDLWindowSystem()->GetWindowSize(&w,&h);
    float unit=std::max(320,std::min(w,h));
    *x=std::clamp(panX/(unit*dt),-1.f,1.f); *y=std::clamp(panY/(unit*dt),-1.f,1.f);
    *z=zoomDelta; *r=rotationDelta;
    panX=panY=zoomDelta=rotationDelta=0;
}
extern "C" void KfxAndroidApplyCamera(void) {
    float x=0,y=0,z=0,r=0;
    KfxAndroidCameraInputs(&x,&y,&z,&r);
    if (!local_state.camera.ready) return;
    auto* player=get_my_player();
    int view=get_local_view_type(player);
    if (view != PVT_DungeonTop && view != PVT_MapScreen) return;
    if (x != 0 || y != 0) {
        local_state.camera_movement_x=x;
        local_state.camera_movement_y=y;
    }
    int index=static_cast<int>(get_local_active_camera(player)-local_state.camera.current);
    if (index<0 || index>=CamIV_EndList) return;
    auto* camera=&local_state.camera.destination[index];
    if (r != 0) camera->rotation_angle_x=(camera->rotation_angle_x+static_cast<int>(std::lround(r*2048.0/360.0))) & 2047;
    if (z != 0) {
        bool front=camera->view_mode==PVM_FrontView;
        int lo=static_cast<int>(scale_camera_zoom_to_screen(front?FRONTVIEW_CAMERA_ZOOM_MIN:CAMERA_ZOOM_MIN));
        int hi=static_cast<int>(scale_camera_zoom_to_screen(front?FRONTVIEW_CAMERA_ZOOM_MAX:CAMERA_ZOOM_MAX));
        camera->zoom=std::clamp(static_cast<int>(std::lround(camera->zoom*std::exp(z*.9f))),lo,hi);
    }
}
