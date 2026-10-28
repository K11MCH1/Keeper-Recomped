cmake_minimum_required(VERSION 3.22)
project(KeeperRecompedAndroid LANGUAGES C CXX)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "This target builds Android arm64-v8a only")
endif()
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF)
get_filename_component(KFX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/keeperfx" ABSOLUTE)
get_filename_component(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party" ABSOLUTE)

find_package(SDL3 CONFIG REQUIRED)
find_package(SDL3_image CONFIG REQUIRED)
find_package(SDL3_mixer CONFIG REQUIRED)
find_package(OpenAL CONFIG REQUIRED)
find_package(CURL CONFIG REQUIRED)
find_package(ZLIB REQUIRED)
find_package(SPNG CONFIG REQUIRED)
find_package(FFMPEG REQUIRED)
find_library(MINIZIP_LIBRARY NAMES minizip REQUIRED)
find_library(UPNP_LIBRARY NAMES miniupnpc REQUIRED)
find_library(NATPMP_LIBRARY NAMES natpmp REQUIRED)

# Small upstream libraries with no Android prebuilt archives.
add_subdirectory("${THIRD_PARTY}/enet6" "${CMAKE_BINARY_DIR}/enet6" EXCLUDE_FROM_ALL)
add_library(centijson STATIC
  "${THIRD_PARTY}/centijson/src/json.c" "${THIRD_PARTY}/centijson/src/json-dom.c"
  "${THIRD_PARTY}/centijson/src/json-ptr.c" "${THIRD_PARTY}/centijson/src/value.c")
target_include_directories(centijson PUBLIC "${THIRD_PARTY}/centijson/src")
add_library(astronomy STATIC "${THIRD_PARTY}/astronomy/source/c/astronomy.c")
target_include_directories(astronomy PUBLIC "${THIRD_PARTY}/astronomy/source/c")
add_library(centitoml STATIC "${KFX_ROOT}/deps/centitoml/toml_api.c")
target_include_directories(centitoml PUBLIC "${KFX_ROOT}/deps/centitoml")
target_link_libraries(centitoml PUBLIC centijson)
file(GLOB LUA_SOURCES "${THIRD_PARTY}/lua-5.1.5/src/*.c")
list(FILTER LUA_SOURCES EXCLUDE REGEX "/(lua|luac|print)\\.c$")
add_library(lua51 STATIC ${LUA_SOURCES} "${THIRD_PARTY}/LuaBitOp-1.0.4/bit.c" src/LuaCompat.c)
target_include_directories(lua51 PUBLIC "${THIRD_PARTY}/lua-5.1.5/src")
target_include_directories(lua51 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_compile_definitions(lua51 PRIVATE LUA_USE_POSIX LUA_USE_DLOPEN)
target_link_libraries(lua51 PUBLIC dl m)

# Existing OpenGL code remains compilable but is never selected on Android.
# The native Android presentation path explicitly selects SDL's Vulkan backend.
add_library(glad STATIC "${KFX_ROOT}/deps/glad/src/glad.c")
target_include_directories(glad PUBLIC "${KFX_ROOT}/deps/glad/include")
file(GLOB_RECURSE ENGINE_C CONFIGURE_DEPENDS "${KFX_ROOT}/src/*.c")
file(GLOB_RECURSE ENGINE_CPP CONFIGURE_DEPENDS "${KFX_ROOT}/src/*.cpp")
list(FILTER ENGINE_C EXCLUDE REGEX "/ftests/")
list(FILTER ENGINE_CPP EXCLUDE REGEX "/ftests/|/PlatformWindows\\.cpp$|/WindowCompositorWin\\.cpp$")

file(STRINGS "${KFX_ROOT}/version.mk" VERSION_LINES)
foreach(LINE IN LISTS VERSION_LINES)
  if(LINE MATCHES "^(VER_MAJOR|VER_MINOR|VER_RELEASE|VER_BUILD)=([0-9]+)")
    set(${CMAKE_MATCH_1} "${CMAKE_MATCH_2}")
  endif()
endforeach()
set(BUILD_NUMBER ${VER_BUILD})
set(PACKAGE_SUFFIX "Android ARM64")
set(VER_STRING "${VER_MAJOR}.${VER_MINOR}.${VER_RELEASE}.${VER_BUILD} ${PACKAGE_SUFFIX}")
set(GIT_REVISION "da038f03ab5f472dc570045f49cbfa76e7101ec4+android")
configure_file("${KFX_ROOT}/ver_defs.h.in" "${CMAKE_BINARY_DIR}/generated/ver_defs.h")
file(READ "${KFX_ROOT}/res/keeperfx_icon256-24bpp.png" ICON_HEX HEX)
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," ICON_BYTES "${ICON_HEX}")
file(WRITE "${CMAKE_BINARY_DIR}/generated/window_icon.c"
  "const unsigned char kfx_window_icon_png[] = {${ICON_BYTES}};\nconst unsigned int kfx_window_icon_png_size = sizeof(kfx_window_icon_png);\n")

add_library(keeperfx SHARED ${ENGINE_C} ${ENGINE_CPP}
  "${CMAKE_BINARY_DIR}/generated/window_icon.c"
  src/AndroidEntry.cpp src/TouchGestures.cpp src/TouchBridge.cpp)
target_include_directories(keeperfx PRIVATE "${KFX_ROOT}/src" "${CMAKE_BINARY_DIR}/generated"
  "${CMAKE_CURRENT_SOURCE_DIR}/src" ${FFMPEG_INCLUDE_DIRS})
target_compile_definitions(keeperfx PRIVATE KFX_ANDROID=1 DEBUG=0 BFDEBUG_LEVEL=0)
target_compile_options(keeperfx PRIVATE -fwrapv -fno-strict-aliasing -fsigned-char
  -Wno-pragma-pack -Wno-address-of-packed-member -Wno-unused-command-line-argument
  -include "${CMAKE_CURRENT_SOURCE_DIR}/src/LuaCompat.h")
target_link_options(keeperfx PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
target_link_directories(keeperfx PRIVATE ${FFMPEG_LIBRARY_DIRS})
target_link_libraries(keeperfx PRIVATE SDL3::SDL3 SDL3_image::SDL3_image SDL3_mixer::SDL3_mixer
  OpenAL::OpenAL CURL::libcurl ZLIB::ZLIB spng::spng_static
  ${FFMPEG_LIBRARIES} ${MINIZIP_LIBRARY} ${UPNP_LIBRARY} ${NATPMP_LIBRARY}
  enet6 centijson centitoml astronomy lua51 glad android log dl m)
set_target_properties(keeperfx PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../build/android/native/arm64-v8a")

option(KFX_ANDROID_SAVE_TESTS "Cross-compile the upstream save codec tests" ON)
if(KFX_ANDROID_SAVE_TESTS)
  file(GLOB SAVE_SOURCES "${KFX_ROOT}/src/kfx/save/core/*.cpp" "${KFX_ROOT}/src/kfx/save/core/schema/*.c")
  add_executable(keeper-save-tests "${KFX_ROOT}/tests/save/kfx_save_tests.c"
    "${KFX_ROOT}/tools/save-tool/save_digest.c" "${KFX_ROOT}/tools/save-tool/save_validate.c" ${SAVE_SOURCES})
  target_include_directories(keeper-save-tests PRIVATE "${KFX_ROOT}/src" "${KFX_ROOT}/tools/save-tool" "${KFX_ROOT}/deps/centitoml" "${CMAKE_BINARY_DIR}/generated")
  target_compile_definitions(keeper-save-tests PRIVATE BFDEBUG_LEVEL=0 DEBUG=0)
  target_compile_options(keeper-save-tests PRIVATE -fsigned-char -Wno-pragma-pack)
  target_link_libraries(keeper-save-tests PRIVATE ZLIB::ZLIB SDL3::SDL3)
  set_target_properties(keeper-save-tests PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../build/tests")
  add_executable(keeper-lua-tests tests/lua_tests.c)
  target_include_directories(keeper-lua-tests PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(keeper-lua-tests PRIVATE lua51)
  set_target_properties(keeper-lua-tests PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../build/tests")
endif()
