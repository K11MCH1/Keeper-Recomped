#include "LuaCompat.h"

/* Standard Lua 5.1 API implementation of the registration operation also
 * provided by LuaJIT and Lua 5.2. Copies shared upvalues for each closure and
 * preserves the caller's table. The Android build uses the Lua 5.1 interpreter
 * plus the upstream BitOp module; it does not expose JIT or FFI functionality.
 */
void KfxLuaSetFuncs(lua_State* L, const luaL_Reg* functions, int upvalues) {
    luaL_checkstack(L, upvalues + 1, "too many upvalues");
    for (const luaL_Reg* function=functions; function->name; ++function) {
        for (int index=0; index<upvalues; ++index) lua_pushvalue(L, -upvalues);
        lua_pushcclosure(L, function->func, upvalues);
        lua_setfield(L, -(upvalues + 2), function->name);
    }
    lua_pop(L, upvalues);
}
