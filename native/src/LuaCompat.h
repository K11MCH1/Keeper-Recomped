#ifndef KFX_LUA_COMPAT_H
#define KFX_LUA_COMPAT_H
#ifdef __cplusplus
extern "C" {
#endif
#include <lua.h>
#include <lauxlib.h>
#include <lualib.h>
#ifndef LUA_OK
#define LUA_OK 0
#endif
#ifndef LUA_BITLIBNAME
#define LUA_BITLIBNAME "bit"
int luaopen_bit(lua_State* L);
#endif
void KfxLuaSetFuncs(lua_State* L, const luaL_Reg* functions, int upvalues);
#ifndef luaL_setfuncs
#define luaL_setfuncs KfxLuaSetFuncs
#define luaL_newlib(L, f) (lua_createtable((L), 0, (int)(sizeof(f)/sizeof((f)[0])-1)), KfxLuaSetFuncs((L),(f),0))
#endif
#ifdef __cplusplus
}
#endif
#endif
