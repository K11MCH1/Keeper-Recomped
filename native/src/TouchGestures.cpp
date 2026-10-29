#include "TouchGestures.hpp"
#include <algorithm>
#include <cmath>
#include <utility>

namespace keeper::touch {
static float distance(float x, float y) { return std::hypot(x, y); }
static float angleDifference(float a, float b) { return std::remainder(a - b, 360.0f); }
void Gestures::emit(Kind k, Point p, float dx, float dy) { actions.push_back({k, p.x, p.y, dx, dy}); }
std::vector<Action> Gestures::take() { return std::exchange(actions, {}); }
void Gestures::releaseHeld() {
    if (mode == Mode::LeftHeld) emit(Kind::LeftUp, last);
    if (mode == Mode::RightHeld) emit(Kind::RightUp, centroid);
    if (mode == Mode::Drag) emit(Kind::DragUp, last);
}
bool Gestures::captureAsDrag() {
    if (mode != Mode::One || fingers.size() != 1) return false;
    mode = Mode::Drag;
    return true;
}
void Gestures::cancel() {
    releaseHeld();
    fingers.clear();
    mode = Mode::Idle;
}
void Gestures::twoGeometry(Point& center, float& length, float& degrees) const {
    auto first = fingers.begin(); auto second = std::next(first);
    center = {(first->second.x + second->second.x) * .5f, (first->second.y + second->second.y) * .5f};
    float dx = second->second.x - first->second.x, dy = second->second.y - first->second.y;
    length = distance(dx, dy);
    degrees = std::atan2(dy, dx) * 180.0f / 3.14159265358979323846f;
}
void Gestures::down(int64_t id, float x, float y, uint64_t ms) {
    if (fingers.count(id)) return;
    fingers[id] = {x, y};
    if (mode == Mode::Blocked) return;
    if (fingers.size() > 2) { releaseHeld(); mode=Mode::Blocked; return; }
    if (fingers.size() == 1) {
        mode = Mode::One; start = last = {x, y}; began = ms; moved = false;
        emit(Kind::Hover, last);
    } else {
        releaseHeld();
        mode = Mode::Two; began = ms; moved = false;
        twoGeometry(centroid, span, angle);
        start = last = centroid; previousSpan = span; previousAngle = angle;
        emit(Kind::Hover, centroid);
    }
}
void Gestures::move(int64_t id, float x, float y, uint64_t ms) {
    auto it = fingers.find(id); if (it == fingers.end()) return;
    it->second = {x, y};
    if (mode == Mode::Idle || mode == Mode::Blocked) return;
    if (fingers.size() == 1) {
        Point p{x,y};
        if (distance(x-start.x,y-start.y) > cfg.slop) {
            moved = true;
            if (mode == Mode::One) {
                mode = panEnabled ? Mode::Pan : Mode::LeftHeld;
                if (!panEnabled) emit(Kind::LeftDown, start);
            }
        }
        if (mode == Mode::Pan) emit(Kind::Pan, p, x-last.x, y-last.y);
        else emit(Kind::Hover, p);
        last = p;
    } else if (fingers.size() == 2) {
        Point center; float length, degrees;
        twoGeometry(center, length, degrees);
        float scale = span >= cfg.slop ? std::log(std::max(length, 1.0f)/span) : 0;
        float rotation = angleDifference(degrees, angle);
        if (std::fabs(scale) > cfg.pinch || std::fabs(rotation) > cfg.rotate || distance(center.x-start.x,center.y-start.y) > cfg.slop) moved = true;
        if (mode == Mode::Two && span >= cfg.slop * 2) {
            // Pinch and rotation are exclusive until every participating finger lifts. The
            // gesture becomes whichever has gone further past its own dead zone: a zoom with
            // angle jitter stays a zoom, and a wrist twist that also changes the finger
            // spacing (most do) still rotates.
            float pinched = std::fabs(scale) / cfg.pinch, turned = std::fabs(rotation) / cfg.rotate;
            if (std::max(pinched, turned) >= 1) mode = turned > pinched ? Mode::Rotate : Mode::Pinch;
        } else if (mode == Mode::Pinch && std::fabs(rotation) >= cfg.rotate * 3) {
            mode = Mode::Rotate; // the fingers spread first, but it is clearly a twist
        }
        if (mode == Mode::Pinch && previousSpan > 0 && length > 0) emit(Kind::Zoom, center, std::log(length/previousSpan));
        else if (mode == Mode::Rotate) emit(Kind::Rotate, center, angleDifference(degrees, previousAngle));
        else if (mode == Mode::Two && moved && panEnabled) emit(Kind::Pan, center, center.x-last.x, center.y-last.y);
        else emit(Kind::Hover, center);
        last = centroid = center; previousSpan = length; previousAngle = degrees;
    }
    tick(ms);
}
void Gestures::up(int64_t id, float x, float y, uint64_t ms) {
    if (!fingers.count(id)) return;
    // Account for final motion before deciding whether this was a tap.
    move(id, x, y, ms);
    if (mode == Mode::One && !moved) emit(Kind::LeftClick, {x,y});
    else if (mode == Mode::Two && !moved) emit(Kind::RightClick, centroid);
    releaseHeld();
    fingers.erase(id);
    // Never reinterpret the surviving finger of a multi-touch gesture as a new tap.
    mode = fingers.empty() ? Mode::Idle : Mode::Blocked;
}
void Gestures::tick(uint64_t ms) {
    if (moved || ms < began || ms-began < cfg.holdMs) return;
    if (mode == Mode::One) { mode = Mode::LeftHeld; emit(Kind::LeftDown, last); }
    else if (mode == Mode::Two) { mode = Mode::RightHeld; emit(Kind::RightDown, centroid); }
}
}
