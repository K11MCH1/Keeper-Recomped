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
#include <algorithm>
#include <cmath>

using namespace keeper::touch;
static Gestures gestures;
static float panX = 0, panY = 0, zoomDelta = 0, rotationDelta = 0;
static uint64_t lastDrain = 0;

extern "C" void KfxAndroidConfigureTouch(float slop, float pinch, float rotate) {
    gestures.configure({slop, pinch, rotate, 450});
}
static void position(float x, float y) {
    int ww = 0, wh = 0;
    GetSDLWindowSystem()->GetWindowSize(&ww, &wh);
    int rx=0, ry=0, rw=ww, rh=wh;
    GetSDLWindowSystem()->GetPresentRect(&rx, &ry, &rw, &rh);
    float sx=1, sy=1; GetSDLWindowSystem()->GetPixelsPerWindowUnit(&sx, &sy);
    float gx = rw > 0 ? (x*sx-rx)*RendererPhysicalWidth()/rw : x;
    float gy = rh > 0 ? (y*sy-ry)*RendererPhysicalHeight()/rh : y;
    pointerHandler.SetMousePosition(std::clamp(static_cast<int>(gx), 0, std::max(0, static_cast<int>(RendererPhysicalWidth())-1)),
                                    std::clamp(static_cast<int>(gy), 0, std::max(0, static_cast<int>(RendererPhysicalHeight())-1)));
}
static void button(unsigned action) { TbPoint delta{0,0}; mouseControl(action, &delta); }
static void drain() {
    for (const auto& a : gestures.take()) {
        position(a.x,a.y);
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
    if (e->type == SDL_EVENT_WINDOW_FOCUS_LOST || e->type == SDL_EVENT_WILL_ENTER_BACKGROUND || e->type == SDL_EVENT_FINGER_CANCELED) {
        gestures.cancel(); drain(); panX=panY=zoomDelta=rotationDelta=0; return 0;
    }
    if (e->type != SDL_EVENT_FINGER_DOWN && e->type != SDL_EVENT_FINGER_UP && e->type != SDL_EVENT_FINGER_MOTION) return 0;
    int w=0,h=0; GetSDLWindowSystem()->GetWindowSize(&w,&h);
    float x=e->tfinger.x*w, y=e->tfinger.y*h;
    if (e->type == SDL_EVENT_FINGER_DOWN) {
        // Menus and the sidebar retain normal mouse dragging; world drags pan.
        const auto* player = get_my_player();
        bool world = player && (get_local_view_type(player) == PVT_DungeonTop || get_local_view_type(player) == PVT_MapScreen);
        float logicalX = w > 0 ? x*RendererPhysicalWidth()/w : x;
        gestures.setPanEnabled(world && logicalX >= lbDisplay.GraphicsWindowX);
        gestures.down(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    } else if (e->type == SDL_EVENT_FINGER_UP) gestures.up(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    else gestures.move(static_cast<int64_t>(e->tfinger.fingerID),x,y,SDL_GetTicks());
    drain(); return 1;
}
extern "C" void KfxAndroidTouchTick(void) { gestures.tick(SDL_GetTicks()); drain(); }
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
