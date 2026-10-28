package org.keeperrecomped.launcher;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the game inside the folder the player picked (Storage Access Framework)
 * and copies it into app storage. Recognised layouts:
 * <ul>
 * <li>Dungeon Keeper (EA/Origin "Dungeon Keeper/DATA", GOG "Dungeon Keeper Gold", CD):
 *     a folder holding {@code data/bluepal.dat} and {@code sound/}. Copied to
 *     {@link Paths#dk} with lowercase names, as the KeeperFX launcher does.</li>
 * <li>A KeeperFX installation (the same plus {@code fxdata/}), e.g. copied from a PC.
 *     Copied to {@link Paths#kfx} as is, without Windows binaries.</li>
 * </ul>
 */
final class Importer {
    interface Progress {
        /** False cancels. */
        boolean update(long doneBytes, long totalBytes, String file);
    }

    static final class Node {
        final String docId, name;
        final boolean dir;
        final long size;
        List<Node> children;

        Node(String docId, String name, boolean dir, long size) {
            this.docId = docId; this.name = name; this.dir = dir; this.size = size;
        }

        Node child(String lower) {
            if (children != null)
                for (Node n : children)
                    if (n.name.toLowerCase(Locale.ROOT).equals(lower))
                        return n;
            return null;
        }
    }

    static final class Found {
        Node root;
        String path;      // display path relative to the picked folder
        boolean keeperfx; // a KeeperFX installation rather than plain Dungeon Keeper
        boolean hasExe;   // KEEPER.EXE / KEEPER95.EXE / DEEPER.EXE present (original-recompilation input)
        long bytes;
        int files;
    }

    private static final int MAX_SEARCH_DEPTH = 4;
    private static final int MAX_FILES = 60000;

    private final ContentResolver resolver;
    private final Uri tree;
    private int listed;

    Importer(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
    }

    private void list(Node dir) throws IOException {
        if (dir.children != null)
            return;
        dir.children = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.docId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
        try (Cursor c = resolver.query(uri, cols, null, null, null)) {
            if (c == null)
                throw new IOException("Cannot list the chosen folder");
            while (c.moveToNext()) {
                if (++listed > MAX_FILES)
                    throw new IOException("The chosen folder is too large; pick the game folder itself");
                boolean isDir = Document.MIME_TYPE_DIR.equals(c.getString(2));
                dir.children.add(new Node(c.getString(0), c.getString(1), isDir, c.isNull(3) ? 0 : c.getLong(3)));
            }
        }
    }

    private boolean isGameRoot(Node dir) throws IOException {
        list(dir);
        Node data = dir.child("data"), sound = dir.child("sound");
        if (data == null || !data.dir || sound == null || !sound.dir)
            return false;
        list(data);
        return data.child("bluepal.dat") != null;
    }

    /** Breadth-first search for the game root, then a full listing of it. */
    Found find() throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Node top = new Node(rootId, "", true, 0);
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{top, "", 0});
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Node dir = (Node) item[0];
            String path = (String) item[1];
            int depth = (Integer) item[2];
            if (isGameRoot(dir)) {
                Found f = new Found();
                f.root = dir;
                f.path = path.isEmpty() ? "/" : path;
                f.keeperfx = dir.child("fxdata") != null;
                f.hasExe = dir.child("keeper.exe") != null || dir.child("keeper95.exe") != null || dir.child("deeper.exe") != null;
                measure(f, dir, true);
                return f;
            }
            if (depth < MAX_SEARCH_DEPTH)
                for (Node n : dir.children)
                    if (n.dir && !skipDir(n.name))
                        queue.add(new Object[]{n, path.isEmpty() ? n.name : path + "/" + n.name, depth + 1});
        }
        throw new IOException("No Dungeon Keeper files found. Pick the folder that contains DATA and SOUND "
                + "(for the EA/Origin version: \"Dungeon Keeper/DATA\"), or a KeeperFX folder.");
    }

    private void measure(Found f, Node dir, boolean top) throws IOException {
        list(dir);
        for (Node n : dir.children) {
            if (n.dir) {
                if (!skipDir(n.name))
                    measure(f, n, false);
            } else if (!skipFile(n.name, f.keeperfx)) {
                f.files++;
                f.bytes += n.size;
            }
        }
    }

    static boolean skipDir(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("dosbox") || n.startsWith(".") || n.equals("__installer") || n.equals("support");
    }

    /** Windows binaries and launcher leftovers are of no use on Android. */
    static boolean skipFile(String name, boolean keeperfx) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("thumbs.db") || n.equals("desktop.ini") || n.endsWith(".part"))
            return true;
        if (!keeperfx)
            return false; // keep KEEPER.EXE etc. for the original recompilation
        return n.endsWith(".exe") || n.endsWith(".dll") || n.endsWith(".map") || n.endsWith(".log")
                || n.startsWith("unins") || n.startsWith("keeperfx-launcher") || n.equals("launch.sh");
    }

    /** Copies the found tree to {@code dest}; DK files get lowercase names. */
    void copy(Found found, File dest, Progress progress) throws IOException {
        long[] done = {0};
        copyDir(found, found.root, dest, !found.keeperfx, done, progress);
    }

    private void copyDir(Found f, Node dir, File dest, boolean lowercase, long[] done, Progress progress) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs())
            throw new IOException("Cannot create " + dest);
        for (Node n : dir.children) {
            String name = lowercase ? n.name.toLowerCase(Locale.ROOT) : n.name;
            if (name.contains("/") || name.equals("..") || name.equals("."))
                continue;
            if (n.dir) {
                if (!skipDir(n.name)) {
                    list(n);
                    copyDir(f, n, new File(dest, name), lowercase, done, progress);
                }
                continue;
            }
            if (skipFile(n.name, f.keeperfx))
                continue;
            File out = new File(dest, name);
            if (out.isFile() && out.length() == n.size && n.size > 0) {
                done[0] += n.size; // already imported
            } else {
                File part = new File(dest, name + ".part");
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, n.docId);
                try (InputStream in = resolver.openInputStream(uri); OutputStream o = new FileOutputStream(part)) {
                    if (in == null)
                        throw new IOException("Cannot read " + n.name);
                    byte[] buf = new byte[1 << 18];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        o.write(buf, 0, r);
                        done[0] += r;
                    }
                }
                if (out.exists())
                    out.delete();
                if (!part.renameTo(out))
                    throw new IOException("Cannot write " + out);
            }
            if (progress != null && !progress.update(done[0], f.bytes, n.name))
                throw new IOException("cancelled");
        }
    }
}
