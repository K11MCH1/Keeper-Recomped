package org.keeperrecomped.launcher;

/** 7z extraction through libkr7z.so (LZMA SDK). */
final class SevenZip {
    static {
        System.loadLibrary("kr7z");
    }

    private SevenZip() {}

    interface Callback {
        /** Destination path relative to the target directory, or null to skip this entry. */
        String map(String path, long size);

        /** False cancels the extraction. */
        boolean progress(long done, long total);
    }

    /** @return null on success, else an error message */
    static native String extract(String archive, String destDir, Callback callback);

    /** Largest unpacked solid block in bytes, or -1 if the archive cannot be read. */
    static native long largestBlock(String archive);
}
