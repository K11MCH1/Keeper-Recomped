#pragma once
#include <cstdint>
#include <map>
#include <vector>

namespace keeper::touch {
enum class Kind { Hover, LeftClick, RightClick, LeftDown, LeftUp, RightDown, RightUp, Pan, Zoom, Rotate };
struct Action { Kind kind; float x, y, dx = 0, dy = 0; };
struct Settings { float slop = 24, pinch = 0.06f, rotate = 12; uint64_t holdMs = 450; };
class Gestures {
public:
    explicit Gestures(Settings settings = {}) : cfg(settings) {}
    void down(int64_t id, float x, float y, uint64_t ms);
    void move(int64_t id, float x, float y, uint64_t ms);
    void up(int64_t id, float x, float y, uint64_t ms);
    void tick(uint64_t ms);
    void cancel();
    void setPanEnabled(bool enabled) { panEnabled = enabled; }
    void configure(Settings settings) { cfg = settings; }
    std::vector<Action> take();
private:
    enum class Mode { Idle, One, LeftHeld, Pan, Two, RightHeld, Pinch, Rotate, Blocked };
    struct Point { float x, y; };
    Settings cfg;
    Mode mode = Mode::Idle;
    std::map<int64_t, Point> fingers;
    std::vector<Action> actions;
    Point start{}, last{}, centroid{};
    float span = 0, angle = 0, previousSpan = 0, previousAngle = 0;
    uint64_t began = 0;
    bool moved = false, panEnabled = true;
    void emit(Kind kind, Point p, float dx = 0, float dy = 0);
    void twoGeometry(Point& center, float& distance, float& degrees) const;
    void releaseHeld();
};
}
