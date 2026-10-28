// 7z extraction for the launcher: KeeperFX releases, alpha patches and workshop
// items are .7z archives. Built on the public-domain LZMA SDK (7-Zip 25.01, C/).
//
// Java: org.keeperrecomped.launcher.SevenZip
//   static native String extract(String archive, String destDir, Callback cb);
// cb.map(path, size) returns the destination path relative to destDir, or null
// to skip the entry; cb.progress(doneBytes, totalBytes) returns false to cancel.
// Returns null on success, otherwise an error message.

#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>

#include "7z.h"
#include "7zAlloc.h"
#include "7zBuf.h"
#include "7zCrc.h"
#include "7zFile.h"

#define TAG "KeeperLauncher"
#define kInputBufSize ((size_t)1 << 18)

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

static int utf16_to_utf8(const UInt16 *src, char *dst, size_t cap)
{
    size_t o = 0;
    for (size_t i = 0; src[i]; i++) {
        UInt32 c = src[i];
        if (c >= 0xD800 && c < 0xDC00 && src[i + 1] >= 0xDC00 && src[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (src[i + 1] - 0xDC00);
            i++;
        }
        char buf[4];
        int n;
        if (c < 0x80) { buf[0] = (char)c; n = 1; }
        else if (c < 0x800) { buf[0] = (char)(0xC0 | (c >> 6)); buf[1] = (char)(0x80 | (c & 0x3F)); n = 2; }
        else if (c < 0x10000) { buf[0] = (char)(0xE0 | (c >> 12)); buf[1] = (char)(0x80 | ((c >> 6) & 0x3F)); buf[2] = (char)(0x80 | (c & 0x3F)); n = 3; }
        else { buf[0] = (char)(0xF0 | (c >> 18)); buf[1] = (char)(0x80 | ((c >> 12) & 0x3F)); buf[2] = (char)(0x80 | ((c >> 6) & 0x3F)); buf[3] = (char)(0x80 | (c & 0x3F)); n = 4; }
        if (o + n + 1 > cap)
            return -1;
        memcpy(dst + o, buf, n);
        o += n;
    }
    dst[o] = 0;
    for (size_t i = 0; i < o; i++)
        if (dst[i] == '\\')
            dst[i] = '/';
    return 0;
}

// Rejects absolute paths and parent-directory components.
static int safe_relative(const char *path)
{
    if (!path[0] || path[0] == '/')
        return 0;
    const char *p = path;
    while (*p) {
        const char *slash = strchr(p, '/');
        size_t len = slash ? (size_t)(slash - p) : strlen(p);
        if (len == 2 && p[0] == '.' && p[1] == '.')
            return 0;
        if (!slash)
            break;
        p = slash + 1;
    }
    return 1;
}

static int make_parents(char *path)
{
    for (char *p = path + 1; *p; p++) {
        if (*p != '/')
            continue;
        *p = 0;
        int r = mkdir(path, 0755);
        *p = '/';
        if (r != 0 && errno != EEXIST)
            return -1;
    }
    return 0;
}

static const char *res_text(SRes res)
{
    switch (res) {
    case SZ_ERROR_UNSUPPORTED: return "unsupported compression method";
    case SZ_ERROR_MEM: return "out of memory";
    case SZ_ERROR_CRC: return "CRC error (corrupt download)";
    case SZ_ERROR_DATA: return "data error (corrupt download)";
    case SZ_ERROR_INPUT_EOF: return "archive is truncated";
    case SZ_ERROR_NO_ARCHIVE: return "not a 7z archive";
    case SZ_ERROR_ARCHIVE: return "archive header error";
    case SZ_ERROR_READ: return "read error";
    default: return "7z error";
    }
}

JNIEXPORT jstring JNICALL
Java_org_keeperrecomped_launcher_SevenZip_extract(JNIEnv *env, jclass clazz, jstring jarchive, jstring jdest, jobject cb)
{
    (void)clazz;
    char err[512] = "";
    jclass cbClass = (*env)->GetObjectClass(env, cb);
    jmethodID mMap = (*env)->GetMethodID(env, cbClass, "map", "(Ljava/lang/String;J)Ljava/lang/String;");
    jmethodID mProgress = (*env)->GetMethodID(env, cbClass, "progress", "(JJ)Z");
    if (!mMap || !mProgress)
        return (*env)->NewStringUTF(env, "callback is missing map/progress");

    const char *archive = (*env)->GetStringUTFChars(env, jarchive, NULL);
    const char *dest = (*env)->GetStringUTFChars(env, jdest, NULL);

    CFileInStream archiveStream;
    CLookToRead2 lookStream;
    CSzArEx db;
    ISzAlloc allocImp = g_Alloc, allocTempImp = g_Alloc;
    UInt16 *name16 = NULL;
    size_t name16Len = 0;
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte *outBuffer = NULL;
    size_t outBufferSize = 0;
    int opened = 0;

    if (InFile_Open(&archiveStream.file, archive) != 0) {
        snprintf(err, sizeof err, "cannot open %s: %s", archive, strerror(errno));
        goto done;
    }
    opened = 1;
    FileInStream_CreateVTable(&archiveStream);
    archiveStream.wres = 0;
    LookToRead2_CreateVTable(&lookStream, False);
    lookStream.buf = (Byte *)ISzAlloc_Alloc(&allocImp, kInputBufSize);
    if (!lookStream.buf) {
        snprintf(err, sizeof err, "out of memory");
        goto done;
    }
    lookStream.bufSize = kInputBufSize;
    lookStream.realStream = &archiveStream.vt;
    LookToRead2_INIT(&lookStream)

    CrcGenerateTable();
    SzArEx_Init(&db);
    SRes res = SzArEx_Open(&db, &lookStream.vt, &allocImp, &allocTempImp);
    if (res != SZ_OK) {
        snprintf(err, sizeof err, "open archive: %s", res_text(res));
        goto free_db;
    }

    UInt64 total = 0, done = 0;
    for (UInt32 i = 0; i < db.NumFiles; i++)
        if (!SzArEx_IsDir(&db, i))
            total += SzArEx_GetFileSize(&db, i);

    for (UInt32 i = 0; i < db.NumFiles && !err[0]; i++) {
        if (SzArEx_IsDir(&db, i))
            continue;
        size_t len = SzArEx_GetFileNameUtf16(&db, i, NULL);
        if (len > name16Len) {
            free(name16);
            name16Len = len;
            name16 = (UInt16 *)malloc(name16Len * sizeof(UInt16));
            if (!name16) { snprintf(err, sizeof err, "out of memory"); break; }
        }
        SzArEx_GetFileNameUtf16(&db, i, name16);
        char name[2048];
        if (utf16_to_utf8(name16, name, sizeof name) != 0)
            continue;
        UInt64 size = SzArEx_GetFileSize(&db, i);

        jstring jname = (*env)->NewStringUTF(env, name);
        jstring jmapped = (jstring)(*env)->CallObjectMethod(env, cb, mMap, jname, (jlong)size);
        (*env)->DeleteLocalRef(env, jname);
        if ((*env)->ExceptionCheck(env)) { snprintf(err, sizeof err, "callback failed"); break; }
        if (!jmapped) {
            done += size;
            continue;
        }
        const char *mapped = (*env)->GetStringUTFChars(env, jmapped, NULL);
        char out[4096];
        int ok = safe_relative(mapped);
        snprintf(out, sizeof out, "%s/%s", dest, mapped);
        (*env)->ReleaseStringUTFChars(env, jmapped, mapped);
        (*env)->DeleteLocalRef(env, jmapped);
        if (!ok) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "7z: skipping unsafe path %s", name);
            done += size;
            continue;
        }

        size_t offset = 0, processed = 0;
        res = SzArEx_Extract(&db, &lookStream.vt, i, &blockIndex, &outBuffer, &outBufferSize,
                             &offset, &processed, &allocImp, &allocTempImp);
        if (res != SZ_OK) {
            snprintf(err, sizeof err, "%s: %s", name, res_text(res));
            break;
        }
        if (make_parents(out) != 0) {
            snprintf(err, sizeof err, "mkdir for %s: %s", out, strerror(errno));
            break;
        }
        char tmp[4100];
        snprintf(tmp, sizeof tmp, "%s.part", out);
        FILE *f = fopen(tmp, "wb");
        if (!f) {
            snprintf(err, sizeof err, "create %s: %s", out, strerror(errno));
            break;
        }
        size_t w = processed ? fwrite(outBuffer + offset, 1, processed, f) : 0;
        int closed = fclose(f);
        if (w != processed || closed != 0 || rename(tmp, out) != 0) {
            snprintf(err, sizeof err, "write %s: %s", out, strerror(errno));
            remove(tmp);
            break;
        }
        done += size;
        jboolean go = (*env)->CallBooleanMethod(env, cb, mProgress, (jlong)done, (jlong)total);
        if ((*env)->ExceptionCheck(env) || !go)
            snprintf(err, sizeof err, "cancelled");
    }
    if (!err[0])
        (*env)->CallBooleanMethod(env, cb, mProgress, (jlong)total, (jlong)total);

free_db:
    ISzAlloc_Free(&allocImp, outBuffer);
    SzArEx_Free(&db, &allocImp);
done:
    if (lookStream.buf && opened)
        ISzAlloc_Free(&allocImp, lookStream.buf);
    free(name16);
    if (opened)
        File_Close(&archiveStream.file);
    (*env)->ReleaseStringUTFChars(env, jarchive, archive);
    (*env)->ReleaseStringUTFChars(env, jdest, dest);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    return err[0] ? (*env)->NewStringUTF(env, err) : NULL;
}

// Largest unpacked solid block, so the launcher can warn before a huge allocation.
JNIEXPORT jlong JNICALL
Java_org_keeperrecomped_launcher_SevenZip_largestBlock(JNIEnv *env, jclass clazz, jstring jarchive)
{
    (void)clazz;
    const char *archive = (*env)->GetStringUTFChars(env, jarchive, NULL);
    CFileInStream archiveStream;
    CLookToRead2 lookStream;
    CSzArEx db;
    ISzAlloc allocImp = g_Alloc;
    jlong largest = -1;
    if (InFile_Open(&archiveStream.file, archive) == 0) {
        FileInStream_CreateVTable(&archiveStream);
        archiveStream.wres = 0;
        LookToRead2_CreateVTable(&lookStream, False);
        lookStream.buf = (Byte *)ISzAlloc_Alloc(&allocImp, kInputBufSize);
        if (lookStream.buf) {
            lookStream.bufSize = kInputBufSize;
            lookStream.realStream = &archiveStream.vt;
            LookToRead2_INIT(&lookStream)
            CrcGenerateTable();
            SzArEx_Init(&db);
            if (SzArEx_Open(&db, &lookStream.vt, &allocImp, &allocImp) == SZ_OK) {
                largest = 0;
                for (UInt32 f = 0; f < db.db.NumFolders; f++) {
                    UInt64 s = SzAr_GetFolderUnpackSize(&db.db, f);
                    if ((jlong)s > largest)
                        largest = (jlong)s;
                }
            }
            SzArEx_Free(&db, &allocImp);
            ISzAlloc_Free(&allocImp, lookStream.buf);
        }
        File_Close(&archiveStream.file);
    }
    (*env)->ReleaseStringUTFChars(env, jarchive, archive);
    return largest;
}
