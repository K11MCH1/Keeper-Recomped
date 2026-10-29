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
int KfxAndroidVsync(void);
void KfxAndroidConfigureTouch(float slop, float pinch, float rotate, int rotate_with_fingers);
void KfxAndroidApplyCamera(void);
void KfxAndroidFramePresented(void);
void KfxAndroidDrawOverlay(SDL_Renderer* renderer);
Uint32 KfxAndroidPresentationGeneration(void);
int KfxAndroidInputDiagnostics(void);
int KfxAndroidCheatsEnabled(void);
void KfxAndroidConfigureWindow(SDL_Window* window);
void KfxAndroidStickAxes(float* x, float* y);
void KfxAndroidPossessionLook(float* x, float* y);
void KfxAndroidDrawTouchOverlay(SDL_Renderer* renderer);
void KfxAndroidStartQuietTextInput(void);
#ifdef __cplusplus
}
#endif
