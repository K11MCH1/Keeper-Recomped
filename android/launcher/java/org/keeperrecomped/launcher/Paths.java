package org.keeperrecomped.launcher;

import android.content.Context;

import java.io.File;

/** App-private storage layout (internal storage, case-sensitive). See coordination/CLAUDE.md. */
final class Paths {
    private Paths() {}

    /** The player's imported Dungeon Keeper files, lowercased; read-only after import. */
    static File dk(Context c) { return new File(c.getFilesDir(), "dk"); }

    /** KeeperFX game root: KeeperFX data plus the DK files it needs. */
    static File kfx(Context c) { return new File(c.getFilesDir(), "kfx"); }

    /**
     * Classic profile root for Play Original: hard links to the KeeperFX files with
     * only the original campaign, no mods, and its own saves.
     */
    static File original(Context c) { return new File(c.getFilesDir(), "original"); }

    /** Resumable downloads; removed once extracted unless the player keeps them. */
    static File downloads(Context c) { return new File(c.getFilesDir(), "downloads"); }

    /** What has been prepared, from where, and which versions. */
    static File state(Context c) { return new File(c.getFilesDir(), "state.json"); }

    /** keeperfx.net API responses, for offline display. */
    static File apiCache(Context c) { return new File(c.getCacheDir(), "api"); }

    static File imageCache(Context c) { return new File(c.getCacheDir(), "img"); }
}
