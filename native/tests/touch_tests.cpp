#include "TouchGestures.hpp"
#include <cassert>
#include <cmath>
#include <cstdio>
using namespace keeper::touch;
static int count(const std::vector<Action>& actions, Kind kind) {
    int n=0;for(const auto& a:actions)if(a.kind==kind)n++;return n;
}
int main() {
    // Quick taps click exactly once; final touch-up movement cannot become a tap.
    { Gestures g;g.down(10,100,100,0);g.up(10,101,101,90);auto a=g.take();assert(count(a,Kind::LeftClick)==1);assert(!count(a,Kind::RightClick)); }
    { Gestures g;g.down(10,100,100,0);g.up(10,190,100,90);assert(!count(g.take(),Kind::LeftClick)); }
    // Two-finger contact gets a single right click; lifting order never leaks a left click.
    { Gestures g;g.down(10,100,100,0);g.down(20,200,100,30);g.up(20,200,100,100);g.up(10,100,100,140);auto a=g.take();assert(count(a,Kind::RightClick)==1);assert(!count(a,Kind::LeftClick)); }
    // Stationary holds work without motion events and release on interruption.
    { Gestures g;g.down(1,100,100,0);g.tick(500);assert(count(g.take(),Kind::LeftDown)==1);g.cancel();assert(count(g.take(),Kind::LeftUp)==1);g.down(2,200,200,600);g.up(2,200,200,650);assert(count(g.take(),Kind::LeftClick)==1); }
    { Gestures g;g.down(1,100,100,0);g.down(2,200,100,10);g.tick(500);assert(count(g.take(),Kind::RightDown)==1);g.up(1,100,100,550);g.up(2,200,100,560);auto a=g.take();assert(count(a,Kind::RightUp)==1);assert(!count(a,Kind::RightClick));assert(!count(a,Kind::LeftClick)); }
    // A pinch with angular jitter remains exclusively a pinch, even past the rotation threshold.
    { Gestures g;g.down(1,100,100,0);g.down(2,300,100,10);g.move(2,340,110,100);g.move(2,370,180,200);g.up(2,370,180,250);g.up(1,100,100,260);auto a=g.take();assert(count(a,Kind::Zoom)>=2);assert(!count(a,Kind::Rotate));assert(!count(a,Kind::RightClick)); }
    // Rotation must begin with a stable span, then locks independently of zoom changes.
    { Gestures g;g.down(1,100,100,0);g.down(2,300,100,10);g.move(2,293.185f,151.764f,100);g.move(2,290,170,150);auto a=g.take();assert(count(a,Kind::Rotate)>=1);assert(!count(a,Kind::Zoom)); }
    // A wrist twist that also widens the fingers by 20% over 45 degrees still rotates.
    { Gestures g;g.down(1,100,100,0);g.down(2,300,100,10);
      for(int d=3;d<=45;d+=3){float t=d*3.14159265f/180,r=200+40*d/45.0f;g.move(2,100+r*std::cos(t),100+r*std::sin(t),10+d*10);}
      auto a=g.take();assert(count(a,Kind::Rotate)>=10);assert(!count(a,Kind::Zoom)); }
    // ...and so does one that widens them by 30%.
    { Gestures g;g.down(1,100,100,0);g.down(2,300,100,10);
      for(int d=3;d<=45;d+=3){float t=d*3.14159265f/180,r=200+60*d/45.0f;g.move(2,100+r*std::cos(t),100+r*std::sin(t),10+d*10);}
      auto a=g.take();assert(count(a,Kind::Rotate)>=8);assert(!count(a,Kind::Zoom)); }
    // A twist that began as a zoom (the fingers spread first) turns into a rotation once
    // it is clearly a twist.
    { Gestures g;g.down(1,100,100,0);g.down(2,300,100,10);g.move(2,340,100,50);
      for(int d=3;d<=45;d+=3){float t=d*3.14159265f/180;g.move(2,100+240*std::cos(t),100+240*std::sin(t),50+d*10);}
      auto a=g.take();assert(count(a,Kind::Zoom)>=1);assert(count(a,Kind::Rotate)>=4); }
    // Third-finger noise cancels held buttons and suppresses clicks until all fingers lift.
    { Gestures g;g.down(1,100,100,0);g.down(2,200,100,10);g.tick(500);g.take();g.down(3,250,100,510);assert(count(g.take(),Kind::RightUp)==1);g.up(1,100,100,520);g.up(2,200,100,530);g.up(3,250,100,540);auto a=g.take();assert(!count(a,Kind::LeftClick));assert(!count(a,Kind::RightClick)); }
    // World dragging pans; menu dragging keeps a held left mouse button instead.
    { Gestures g;g.down(1,100,100,0);g.move(1,150,100,60);g.up(1,160,100,100);auto a=g.take();assert(count(a,Kind::Pan));assert(!count(a,Kind::LeftClick));assert(!count(a,Kind::LeftDown)); }
    { Gestures g;g.setPanEnabled(false);g.down(1,100,100,0);g.move(1,150,100,60);g.up(1,160,100,100);auto a=g.take();assert(count(a,Kind::LeftDown)==1);assert(count(a,Kind::LeftUp)==1);assert(!count(a,Kind::Pan)); }
    // Large finger IDs, timestamp rollback, duplicate downs, and unknown ups are harmless.
    { Gestures g;g.down(INT64_MAX,100,100,1000);g.down(INT64_MAX,100,100,1001);g.tick(900);g.up(123,0,0,950);g.up(INT64_MAX,100,100,1100);auto a=g.take();assert(count(a,Kind::LeftClick)==1);assert(!count(a,Kind::LeftDown)); }
    // A captured drag (double tap and hold) hovers instead of panning, never turns into a
    // hold or a tap, and ends with exactly one DragUp; a second finger ends it too.
    { Gestures g;g.down(1,100,100,0);assert(g.captureAsDrag());g.move(1,200,150,60);g.tick(900);g.up(1,220,160,1000);auto a=g.take();assert(count(a,Kind::DragUp)==1);assert(count(a,Kind::Hover)>=2);assert(!count(a,Kind::Pan));assert(!count(a,Kind::LeftDown));assert(!count(a,Kind::LeftClick)); }
    { Gestures g;g.down(1,100,100,0);assert(g.captureAsDrag());g.down(2,200,100,30);assert(count(g.take(),Kind::DragUp)==1);g.up(2,200,100,60);g.up(1,100,100,70);auto a=g.take();assert(!count(a,Kind::DragUp));assert(!count(a,Kind::LeftClick)); }
    { Gestures g;g.down(1,100,100,0);g.move(1,150,100,60);assert(!g.captureAsDrag());g.down(2,200,100,0);assert(!g.captureAsDrag()); }
    std::puts("PASS: 17 touch gesture scenarios on Android ARM64");
}
