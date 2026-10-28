#pragma once
#include <SDL3/SDL.h>
#ifdef __cplusplus
extern "C" {
#endif
int KfxAndroidTouchEvent(const SDL_Event* event);
void KfxAndroidTouchTick(void);
void KfxAndroidCameraInputs(float* pan_x, float* pan_y, float* zoom, float* rotation);
const char* KfxAndroidPresentationDriver(void);
float KfxAndroidRenderScale(void);
int KfxAndroidFrameCap(void);
void KfxAndroidConfigureTouch(float slop, float pinch, float rotate);
void KfxAndroidApplyCamera(void);
void KfxAndroidFramePresented(void);
#ifdef __cplusplus
}
#endif
