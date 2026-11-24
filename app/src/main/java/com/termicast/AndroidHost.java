package com.termicast;

import android.content.Context;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.net.wifi.WifiManager;

import com.termicast.server.Server;
import com.termicast.server.DlnaController;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AndroidHost implements Server.Host {
    private final Context ctx;
    private final DlnaController dlna = new DlnaController();

    public AndroidHost(Context c) { ctx = c.getApplicationContext(); }

    public InputStream asset(String path) throws IOException {
        try { return ctx.getAssets().open(path); } catch (IOException e) { return null; }
    }

    public List<Server.Media> scan() {
        List<Server.Media> out = new ArrayList<Server.Media>();
        List<String> volumes = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 29) {
            try { volumes.addAll(MediaStore.getExternalVolumeNames(ctx)); } catch (Exception ignored) { }
        }
        if (volumes.isEmpty()) volumes.add("external");
        for (String volume : volumes) {
            Uri videos = Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.getContentUri(volume) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri audio = Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.getContentUri(volume) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            query(videos, "video", out);
            query(audio, "audio", out);
        }
        scanPickedFolders(out);
        return out;
    }

    private void scanPickedFolders(List<Server.Media> out) {
        Set<String> trees = ctx.getSharedPreferences("storage", Context.MODE_PRIVATE).getStringSet("trees", new HashSet<String>());
        Set<String> known = new HashSet<String>();
        for (Server.Media m : out) known.add(m.path);
        int added = 0;
        for (String value : trees) {
            try {
                Uri tree = Uri.parse(value);
                String root = DocumentsContract.getTreeDocumentId(tree);
                ArrayDeque<TreeNode> pending = new ArrayDeque<TreeNode>();
                pending.add(new TreeNode(root, 0));
                Set<String> visited = new HashSet<String>();
                while (!pending.isEmpty() && added < 10000) {
                    TreeNode parent = pending.removeFirst();
                    if (parent.depth > 12 || !visited.add(parent.id)) continue;
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id);
                    Cursor c = null;
                    try {
                        c = ctx.getContentResolver().query(children,
                                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                                        DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
                        while (c != null && c.moveToNext() && added < 10000) {
                            String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                            if (id == null) continue;
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                pending.addLast(new TreeNode(id, parent.depth + 1));
                                continue;
                            }
                            String type = mediaType(name, mime);
                            if (type == null) continue;
                            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                            if (!known.add(doc.toString())) continue;
                            Server.Media m = new Server.Media();
                            m.path = doc.toString(); m.name = name == null ? "media" : name;
                            m.type = type; m.size = c.isNull(3) ? 0 : c.getLong(3); m.dur = 0;
                            out.add(m); added++;
                        }
                    } finally { if (c != null) c.close(); }
                }
            } catch (Exception ignored) { }
        }
    }

    private static String mediaType(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (mime != null && mime.startsWith("video/")) return "video";
        if (mime != null && mime.startsWith("audio/")) return "audio";
        if (n.matches(".*\\.(mp4|m4v|mkv|webm|avi|mov|3gp|ts|mpg|mpeg)$")) return "video";
        if (n.matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$")) return "audio";
        if (n.matches(".*\\.(srt|vtt)$")) return "subtitle";
        return null;
    }

    private static final class TreeNode {
        final String id; final int depth;
        TreeNode(String id, int depth) { this.id = id; this.depth = depth; }
    }

    private void query(Uri uri, String type, List<Server.Media> out) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE, "duration"},
                    null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            while (c != null && c.moveToNext()) {
                long rowId = c.getLong(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0 : c.getLong(2);
                long dur = c.isNull(3) ? 0 : c.getLong(3);
                if (type.equals("audio") && (dur < 15000 || size < 100 * 1024)) continue; // skip system sounds
                Server.Media m = new Server.Media();
                m.path = ContentUris.withAppendedId(uri, rowId).toString();
                m.name = name != null ? name : "media-" + rowId;
                m.type = type;
                m.size = size;
                m.dur = dur;
                out.add(m);
            }
        } catch (Exception e) {
            // permission missing -> empty list, UI shows hint
        } finally {
            if (c != null) c.close();
        }
    }

    @Override
    public InputStream open(Server.Media media, long offset) throws IOException {
        ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor(Uri.parse(media.path), "r");
        if (fd == null) throw new FileNotFoundException(media.path);
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
        long remaining = offset;
        try {
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) throw new IOException("Unable to seek media stream");
                    skipped = 1;
                }
                remaining -= skipped;
            }
            return in;
        } catch (IOException e) { in.close(); throw e; }
    }

    @Override
    public boolean delete(Server.Media media) {
        try { return ctx.getContentResolver().delete(Uri.parse(media.path), null, null) > 0; }
        catch (Exception e) { return false; }
    }

    @Override
    public String dlnaScan() {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wm != null) { lock = wm.createMulticastLock("termicast-ssdp"); lock.setReferenceCounted(false); lock.acquire(); }
            return dlna.scanJson();
        } catch (Exception e) { return "[]"; }
        finally { if (lock != null && lock.isHeld()) lock.release(); }
    }

    @Override
    public boolean dlnaPlay(String renderer, String mediaUrl, String mime, String title) {
        return dlna.play(renderer, mediaUrl, mime, title);
    }

    @Override
    public boolean dlnaControl(String renderer, String command, String position) {
        return dlna.control(renderer, command, position);
    }

    @Override
    public String dlnaState(String renderer) { return dlna.stateJson(renderer); }

    public String deviceJson() {
        String abi = Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        return "{\"model\":" + org.json.JSONObject.quote(Build.MODEL == null ? "" : Build.MODEL)
                + ",\"brand\":" + org.json.JSONObject.quote(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                + ",\"android\":" + org.json.JSONObject.quote(Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE)
                + ",\"abi\":" + org.json.JSONObject.quote(abi) + ",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"cpus\":" + Runtime.getRuntime().availableProcessors() + "}";
    }
}
