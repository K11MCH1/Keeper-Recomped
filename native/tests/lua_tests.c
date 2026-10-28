#include "LuaCompat.h"
#include <stdio.h>
#include <stdlib.h>
static int checks=0;
#define CHECK(x) do{checks++;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}}while(0)
static int increment(lua_State* L) { lua_pushinteger(L,luaL_checkinteger(L,1)+lua_tointeger(L,lua_upvalueindex(1)));return 1; }
static void script(lua_State* L,const char* text) {
    int result=luaL_dostring(L,text);if(result)fprintf(stderr,"Lua: %s\n",lua_tostring(L,-1));CHECK(result==0);
}
int main(int argc,char**argv) {
    lua_State* L=luaL_newstate();CHECK(L!=NULL);luaL_openlibs(L);
    lua_pushcfunction(L,luaopen_bit);lua_pushliteral(L,"bit");lua_call(L,1,0);
    script(L,"assert(bit.tobit(0xffffffff)==-1);assert(bit.band(0x12345678,0xff)==0x78);assert(bit.arshift(-8,1)==-4);assert(bit.rol(1,31)==-2147483648);assert(bit.bswap(0x12345678)==0x78563412)");
    static const luaL_Reg functions[]={{"increment",increment},{NULL,NULL}};
    lua_newtable(L);lua_pushinteger(L,7);KfxLuaSetFuncs(L,functions,1);lua_setglobal(L,"testlib");
    script(L,"assert(testlib.increment(5)==12)");
    script(L,"local f=assert(loadstring(string.dump(function(x)return x*3 end)));assert(f(7)==21)");
    script(L,"local t={};for i=1,10000 do t[i]=i*i end;collectgarbage('collect');assert(t[10000]==100000000)");
    script(L,"local t=setmetatable({}, {__mode='kv'});for i=1,10000 do t[{i}]={i} end;collectgarbage('collect');assert(next(t)==nil)");
    if(argc>1){
        lua_pushstring(L,argv[1]);lua_setglobal(L,"fixture");
        script(L,"local b=assert(loadfile(fixture))();local shared={v='alias'};local t={a=shared,b=shared,n=2^40,negative=-1,bytes='a\\0b'};t.self=t;local encoded=b.serialize(t);local out=b.deserialize(encoded)[1];assert(out.a==out.b and out.self==out);assert(out.n==2^40 and out.negative==-1 and out.bytes=='a\\0b')");
    }
    lua_close(L);printf("PASS: %d Lua/BitOp/registration/serialization checks on Android ARM64\n",checks);return 0;
}
