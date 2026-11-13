package com.captioncut.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "CaptionCut";
    private WebView wv;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private static final int PICK_VIDEO = 1001;

    // v1.14: free update distribution — GitHub Releases. The app checks the
    // latest release on launch (max once a day) and shows an in-app banner
    // with a Download button when a newer version exists. No repo yet:
    // until Dilawar creates github.com/iamdilawarkhan/captioncut and puts
    // the first release there, the API call 404s and fails SILENTLY —
    // no popup, no error, nothing changes for the user. One constant to
    // change if the repo name ever differs.
    private static final String UPDATE_REPO = "iamdilawarkhan/captioncut";
    private static final String UPDATE_API =
            "https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest";

    private String currentVideoPath = "";
    private String currentVideoName = "";
    private volatile boolean exportCancel = false;
    private volatile boolean exportRunning = false;
    private String lastExportUri = "";

    // v1.15: in-app update download — DownloadManager id + receiver.
    private long updateDownloadId = -1;
    private String updateDownloadUrl = "";
    private BroadcastReceiver updateDownloadReceiver = null;
    private boolean updateReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.setWebViewClient(new WebViewClient());
        wv.addJavascriptInterface(new Bridge(), "Android");
        setContentView(wv);
        wv.loadUrl("file:///android_asset/www/index.html");
        checkForUpdate(false); // v1.14: silent daily check, banner only if newer
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            pool.execute(() -> {
                try {
                    String name = "video.mp4";
                    try {
                        android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                        if (c != null) {
                            int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                            c.close();
                        }
                    } catch (Exception ignored) { }
                    File dir = new File(getFilesDir(), "videos");
                    dir.mkdirs();
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp4";
                    File out = new File(dir, "imported_" + System.currentTimeMillis() + ext);
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("Could not read this file");
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    in.close(); os.close();
                    currentVideoPath = out.getAbsolutePath();
                    currentVideoName = name;
                    // remember in recent list
                    SharedPreferences p = getSharedPreferences("captioncut", MODE_PRIVATE);
                    String recent = p.getString("recent", "[]");
                    JSONArray arr = new JSONArray(recent);
                    JSONObject item = videoInfoJson(currentVideoPath, currentVideoName);
                    JSONArray nr = new JSONArray();
                    nr.put(item);
                    for (int i = 0; i < arr.length() && i < 4; i++) nr.put(arr.get(i));
                    p.edit().putString("recent", nr.toString()).apply();
                    final String js = "window.__onVideo(" + JSONObject.quote(item.toString()) + ");";
                    wv.post(() -> wv.evaluateJavascript(js, null));
                } catch (Exception e) {
                    Log.e(TAG, "import failed", e);
                    showImportError("Video import failed: " + e.getMessage());
                }
            });
        } else if (requestCode == PICK_VIDEO && resultCode == RESULT_OK) {
            // Picker said OK but gave us no file — say so instead of silence.
            showImportError("Import failed: gallery returned no video");
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    // v1.2: import failures must be VISIBLE in the app (a red line under the
    // Import button), not just a toast that disappears.
    private void showImportError(final String msg) {
        toast(msg);
        runOnUiThread(() -> {
            try {
                wv.evaluateJavascript("window.__importError(" + JSONObject.quote(msg) + ")", null);
            } catch (Exception ignored) { }
        });
    }

    private void jsCallback(final String cb, final String payload) {
        final String js = cb + "(" + JSONObject.quote(payload) + ");";
        wv.post(() -> wv.evaluateJavascript(js, null));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private JSONObject videoInfoJson(String path, String name) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        r.setDataSource(path);
        String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        r.release();
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("name", name);
        o.put("duration", dur == null ? 0 : Long.parseLong(dur) / 1000.0);
        o.put("width", w == null ? 0 : Integer.parseInt(w));
        o.put("height", h == null ? 0 : Integer.parseInt(h));
        o.put("rotation", rot == null ? 0 : Integer.parseInt(rot));
        o.put("size", new File(path).length());
        return o;
    }

    // ---------- export target (MediaStore on API 29+, app files dir below) ----------
    private static class ExportTarget {
        ParcelFileDescriptor pfd; Uri uri; String path; String name;
    }

    private ExportTarget openExportTarget(String name, String mime) throws Exception {
        ExportTarget t = new ExportTarget();
        t.name = name;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CaptionCut");
            Uri uri = getContentResolver().insert(
                    mime.startsWith("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Files.getContentUri("external"), v);
            t.uri = uri;
            t.pfd = getContentResolver().openFileDescriptor(uri, "w");
            t.path = "Movies/CaptionCut/" + name;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CaptionCut");
            dir.mkdirs();
            File f = new File(dir, name);
            t.path = f.getAbsolutePath();
            t.pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            t.uri = Uri.fromFile(f);
        }
        return t;
    }

    private void closeExportTarget(ExportTarget t) {
        try { if (t.pfd != null) t.pfd.close(); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT < 29 && t.path != null) {
            MediaScannerConnection.scanFile(this, new String[]{t.path}, null, null);
        }
        if (t.uri != null) lastExportUri = t.uri.toString();
    }

    // ---------- YUV helpers ----------
    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    // YUV_420_888 Image -> ARGB Bitmap (BT.601 limited range)
    private static Bitmap imageToBitmap(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane yP = img.getPlanes()[0];
        Image.Plane uP = img.getPlanes()[1];
        Image.Plane vP = img.getPlanes()[2];
        ByteBuffer yB = yP.getBuffer(), uB = uP.getBuffer(), vB = vP.getBuffer();
        int yRS = yP.getRowStride(), uRS = uP.getRowStride(), vRS = vP.getRowStride();
        int uPS = uP.getPixelStride(), vPS = vP.getPixelStride();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int yRow = y * yRS;
            int uvRow = (y / 2) * uRS;
            int uvRowV = (y / 2) * vRS;
            for (int x = 0; x < w; x++) {
                int Y = yB.get(yRow + x) & 0xFF;
                int U = uB.get(uvRow + (x / 2) * uPS) & 0xFF;
                int V = vB.get(uvRowV + (x / 2) * vPS) & 0xFF;
                int C = Y - 16;
                int D = U - 128;
                int E = V - 128;
                int R = clamp((298 * C + 409 * E + 128) >> 8);
                int G = clamp((298 * C - 100 * D - 208 * E + 128) >> 8);
                int B = clamp((298 * C + 516 * D + 128) >> 8);
                px[y * w + x] = 0xFF000000 | (R << 16) | (G << 8) | B;
            }
        }
        // v1.1 FIX (export crash "Immutable bitmap passed to Canvas constructor"):
        // Bitmap.createBitmap(int[],...) returns an IMMUTABLE bitmap, and
        // new Canvas(bmp) requires a mutable one. Return a mutable copy.
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        return b.copy(Bitmap.Config.ARGB_8888, true);
    }

    // ARGB Bitmap -> NV12 (YUV420 semi-planar) bytes, even w/h
    private static byte[] bitmapToNv12(Bitmap bmp, int w, int h) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] out = new byte[w * h * 3 / 2];
        int uvOff = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int R = (p >> 16) & 0xFF, G = (p >> 8) & 0xFF, B = p & 0xFF;
                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                out[y * w + x] = (byte) clamp(Y);
                if ((y % 2 == 0) && (x % 2 == 0)) {
                    int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                    int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                    int uvIndex = uvOff + (y / 2) * w + x;
                    out[uvIndex] = (byte) clamp(U);
                    out[uvIndex + 1] = (byte) clamp(V);
                }
            }
        }
        return out;
    }

    // ---------- caption drawing ----------
    // v1.1: caption size is based on the frame's SHORT side — the video WIDTH
    // when portrait (9:16) and the HEIGHT when landscape — so captions sit
    // inside the video frame at the same relative size the preview shows.
    // v1.4: SINGLE LINE ONLY — a caption chunk is NEVER wrapped to 2 lines.
    // The font is shrunk until the whole chunk fits 0.92 * frame width on
    // one line (same rule as the JS preview).
    private static int captionBase(int W, int H) { return (W < H) ? W : H; }

    private static float chunkWidth(Paint paint, String[] words) {
        float w = 0, spaceW = paint.measureText(" ");
        for (int i = 0; i < words.length; i++) w += paint.measureText(words[i]) + (i > 0 ? spaceW : 0);
        return w;
    }

    // Greedy word wrap: returns lines as [startWordIndex, endWordIndex) pairs.
    private static List<int[]> wrapWords(Paint paint, String[] words, float maxW) {
        List<int[]> lines = new ArrayList<int[]>();
        float spaceW = paint.measureText(" ");
        int i = 0;
        while (i < words.length) {
            float w = 0;
            int j = i;
            while (j < words.length) {
                float ww = paint.measureText(words[j]);
                float add = (j == i) ? ww : spaceW + ww;
                if (j > i && w + add > maxW) break;
                w += add;
                j++;
            }
            if (j == i) j = i + 1; // single word wider than the frame: keep it alone
            lines.add(new int[]{i, j});
            i = j;
        }
        return lines;
    }

    // v1.10: is this text RTL (Urdu/Arabic/Hebrew)? The FIRST strong
    // directional character decides; neutrals (spaces, digits, emoji,
    // punctuation) are skipped.
    // v1.11 ROOT CAUSE (found by a real Java test — see BUILD_NOTES):
    // iterating by char is WRONG for emoji. An emoji such as 🎉 is a
    // surrogate PAIR, and Character.getDirectionality() on each surrogate
    // HALF reports LEFT_TO_RIGHT (bidi class L) — so any segment starting
    // with an emoji made isRtl() return false, and the whole segment was
    // drawn left-to-right (words jumbled) even though the v1.10 RTL
    // placement itself is correct. The app prepends one emoji per segment
    // whenever "Include emojis & styling" is ON — which is why the
    // user's export stayed jumbled after v1.10 while the preview (whose
    // JS check is presence-based) was correct. Fixed by iterating by
    // CODE POINT: the emoji code point reports OTHER_NEUTRALS and is
    // skipped, so the first strong letter decides.
    private static boolean isRtl(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
                d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING ||
                d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) return false;
        }
        return false;
    }

    // v1.11: join words back into one string (for a per-chunk / per-line
    // direction check — the JS preview decides direction from the shown
    // chunk, and the export must decide the same way).
    private static String joinWords(String[] words, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) { if (i > from) sb.append(' '); sb.append(words[i]); }
        return sb.toString();
    }
    private static String joinWords(String[] words) { return joinWords(words, 0, words.length); }

    // v1.11: tolerant colour parse. The JS saves hex strings, so
    // parseColor normally just works — but a bad value must NEVER silently
    // become yellow (that is exactly what Dilawar saw). Name map covers
    // all 10 swatch colours; anything else falls back to WHITE.
    private static int parseCaptionColor(String color) {
        if (color == null) return Color.WHITE;
        String c = color.trim();
        try { return Color.parseColor(c); } catch (Exception ignored) { }
        if (c.equalsIgnoreCase("white")) return Color.parseColor("#FFFFFF");
        if (c.equalsIgnoreCase("yellow")) return Color.parseColor("#FFD60A");
        if (c.equalsIgnoreCase("orange")) return Color.parseColor("#FF9800");
        if (c.equalsIgnoreCase("red")) return Color.parseColor("#F44336");
        if (c.equalsIgnoreCase("pink")) return Color.parseColor("#EC4899");
        if (c.equalsIgnoreCase("purple")) return Color.parseColor("#A855F7");
        if (c.equalsIgnoreCase("blue")) return Color.parseColor("#3B82F6");
        if (c.equalsIgnoreCase("cyan")) return Color.parseColor("#06B6D4");
        if (c.equalsIgnoreCase("green")) return Color.parseColor("#22C55E");
        if (c.equalsIgnoreCase("lime")) return Color.parseColor("#A3E635");
        return Color.WHITE;
    }

    // Chunk a segment's words into display groups. Single line = groups of
    // max 4 words; if the last group would be a single orphan word, move one
    // word from the previous group so no group has just 1 word (5 -> 3+2).
    private static List<String[]> chunkWords(String[] words, int maxN) {
        List<String[]> groups = new ArrayList<String[]>();
        for (int i = 0; i < words.length; i += maxN) {
            int n = Math.min(maxN, words.length - i);
            String[] g = new String[n];
            System.arraycopy(words, i, g, 0, n);
            groups.add(g);
        }
        if (maxN >= 4 && groups.size() > 1) {
            String[] last = groups.get(groups.size() - 1);
            if (last.length == 1) {
                String[] prev = groups.get(groups.size() - 2);
                String[] newPrev = new String[prev.length - 1];
                System.arraycopy(prev, 0, newPrev, 0, prev.length - 1);
                String[] newLast = new String[]{ prev[prev.length - 1], last[0] };
                groups.set(groups.size() - 2, newPrev);
                groups.set(groups.size() - 1, newLast);
            }
        }
        return groups;
    }

    private static void drawCaption(Canvas cv, int W, int H, String text, JSONObject style,
                                    double segStart, double segEnd, double nowSec) throws Exception {
        if (text == null || text.isEmpty()) return;
        int sizePct = style.optInt("size", 5);
        boolean bold = style.optBoolean("bold", true);
        String color = style.optString("color", "#FFFFFF");
        boolean bg = style.optBoolean("bg", true);
        String pos = style.optString("position", "bottom");
        // v1.3: emojis ON forces word-highlight ON (preview and export alike).
        boolean hl = style.optBoolean("highlight", true) || style.optBoolean("emojis", false);
        String[] words = text.split("\\s+");
        double prog = (segEnd > segStart) ? (nowSec - segStart) / (segEnd - segStart) : 0;
        int curWord = (int) Math.min(words.length - 1, Math.max(0, prog * words.length));
        // v1.3 caption modes — all three are CHUNKED display of the same
        // segment, each chunk shown for its proportional slice of the
        // segment time: word = 1 word, duo = 2 words, line = 3-4 words
        // per chunk (NOT the whole full segment as one line).
        // In word/duo modes the shown word(s) ARE the highlight; in line
        // mode only the current word inside the chunk is highlighted.
        String mode = style.optString("mode", "line");
        int maxN = "word".equals(mode) ? 1 : "duo".equals(mode) ? 2 : 4;
        List<String[]> groups = chunkWords(words, maxN);
        int gi = Math.min(groups.size() - 1, Math.max(0, (int) (prog * groups.size())));
        words = groups.get(gi);
        int startIdx = 0;
        for (int g = 0; g < gi; g++) startIdx += groups.get(g).length;
        int hlFrom, hlTo; // highlight range inside the shown chunk
        if ("line".equals(mode)) {
            int local = hl ? curWord - startIdx : -1;
            hlFrom = local; hlTo = local + 1;
        } else {
            // v1.11: in word/duo modes the shown words ARE the current
            // words — painting them all with the yellow highlight hid the
            // user's chosen colour entirely (the export stayed yellow no
            // matter which swatch was picked). No highlight here: the
            // whole chunk is drawn in the chosen colour. Line mode keeps
            // its yellow current-word highlight.
            hlFrom = -1; hlTo = -1;
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFakeBoldText(bold);
        paint.setColor(parseCaptionColor(color));
        paint.setTextAlign(Paint.Align.LEFT);
        // v1.4: ONE LINE ONLY. Shrink until the whole chunk fits 92% of
        // frame width on a single line — smaller font is correct, wrapping
        // is never correct. Same rule in the JS preview.
        int textPx = Math.max(14, (int) (captionBase(W, H) * sizePct / 100.0));
        // Fewer words on screen = bigger text. Line chunks are clearly
        // bigger than the old full-line text (which was 1.0x).
        if ("word".equals(mode)) textPx = (int) (textPx * 1.6);
        else if ("duo".equals(mode)) textPx = (int) (textPx * 1.25);
        else textPx = (int) (textPx * 1.3);
        float maxW = W * 0.92f;
        for (int attempt = 0; attempt < 20; attempt++) {
            paint.setTextSize(textPx);
            if (chunkWidth(paint, words) <= maxW || textPx <= 14) break;
            textPx = (int) (textPx * 0.85f);
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float lw = chunkWidth(paint, words);
        float pad = textPx * 0.35f;
        float baseline;
        float cx;
        // v1.8: hand-dragged position. posX/posY are PILL-CENTRE fractions
        // (0.5 = dead centre) — the same centre convention as the JS
        // preview, so the burned-in caption lands where the drag put it.
        // The pill centre is baseline + (ascent+descent)/2 + 0.1*pad
        // (centre of the bg rect), so the baseline is offset back from
        // the centre. Applies in all three modes (they share drawCaption).
        // Without a saved drag, the exact old Top/Center/Bottom behaviour
        // is kept. (v1.7 saved posY as a baseline fraction — old saved
        // values shift by ~0.22 line heights under the new reading.)
        double posX = style.optDouble("posX", -1);
        double posY = style.optDouble("posY", -1);
        if (posX >= 0 && posX <= 1 && posY >= 0 && posY <= 1) {
            cx = (float) (W * posX) - lw / 2f;
            baseline = (float) (H * posY) - (fm.ascent + fm.descent) / 2f - pad * 0.1f;
        } else {
            cx = (W - lw) / 2f;
            if (pos.equals("top")) baseline = H * 0.08f - fm.ascent;
            else if (pos.equals("center")) baseline = H * 0.5f - lineH / 2f - fm.ascent;
            else baseline = H * 0.90f;
        }
        // Clamp the whole pill (text + background pad) inside the frame.
        if (cx < pad) cx = pad;
        if (cx + lw + pad > W) cx = W - lw - pad;
        if (cx < pad) cx = pad;
        if (baseline + fm.ascent - pad * 0.6f < 0) baseline = -fm.ascent + pad * 0.6f;
        if (baseline + fm.descent + pad * 0.8f > H) baseline = H - fm.descent - pad * 0.8f;
        if (bg) {
            Paint bgP = new Paint();
            bgP.setColor(Color.argb(200, 0, 0, 0));
            cv.drawRoundRect(new RectF(cx - pad, baseline + fm.ascent - pad * 0.6f,
                    cx + lw + pad, baseline + fm.descent + pad * 0.8f),
                    textPx * 0.3f, textPx * 0.3f, bgP);
        }
        Paint hlPaint = new Paint(paint);
        hlPaint.setColor(Color.parseColor("#FFD60A"));
        float spaceW = paint.measureText(" ");
        // v1.11: direction is decided from the SHOWN CHUNK (same as the JS
        // preview, which checks show.join(' ')), not the full segment. A
        // mixed-language segment (e.g. English first words, Urdu later
        // chunks) used to force every chunk LTR because the segment's
        // first strong character was Latin — while the preview showed the
        // Urdu chunks RTL. Per-chunk, preview and export can never
        // disagree. (The v1.10 RTL placement itself was verified by test:
        // "آیا ہے" puts آیا rightmost, "مجھے کیسے پتہ" reads correctly —
        // see BUILD_NOTES v1.11. The screenshot's export predates v1.10:
        // file created 13:09:56 PKT, ~2 min after the v1.10 delivery, and
        // captions are not persisted across restarts, so it was made with
        // the pre-fix build.)
        boolean rtlChunk = isRtl(joinWords(words));
        if (rtlChunk) {
            // v1.10: RTL (Urdu) — the first logical word sits at the RIGHT
            // end of the block. Drawing words one by one left-to-right (as
            // before) put the first spoken word on the LEFT, so the whole
            // chunk read backwards in the export. Advance right-to-left
            // instead; the highlight index is still the logical index, so
            // the highlighted word lands on the right word. (Drawing each
            // word in its own drawText call is fine for shaping — letters
            // join within a word, spaces break joining anyway.)
            float x = cx + lw; // right edge of the text block
            for (int i = 0; i < words.length; i++) {
                float w = paint.measureText(words[i]);
                x -= w;
                cv.drawText(words[i], x, baseline, (i >= hlFrom && i < hlTo) ? hlPaint : paint);
                x -= spaceW;
            }
        } else {
            for (int i = 0; i < words.length; i++) {
                if (i > 0) cx += spaceW;
                cv.drawText(words[i], cx, baseline, (i >= hlFrom && i < hlTo) ? hlPaint : paint);
                cx += paint.measureText(words[i]);
            }
        }
    }

    private static void drawTextOverlay(Canvas cv, int W, int H, JSONObject t) throws Exception {
        String text = t.optString("text", "");
        if (text.isEmpty()) return;
        int sizePct = t.optInt("size", 6);
        String color = t.optString("color", "#FFFFFF");
        String pos = t.optString("position", "top");
        String[] words = text.split("\\s+");
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFakeBoldText(true);
        paint.setColor(parseCaptionColor(color));
        paint.setTextAlign(Paint.Align.LEFT);
        int textPx = Math.max(18, (int) (captionBase(W, H) * sizePct / 100.0));
        List<int[]> lines = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            paint.setTextSize(textPx);
            lines = wrapWords(paint, words, W * 0.92f);
            if (lines.size() <= 2 || textPx <= 18) break;
            textPx = (int) (textPx * 0.85f);
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float totalH = lines.size() * lineH;
        float firstBaseline;
        if (pos.equals("bottom")) firstBaseline = H * 0.85f - (lines.size() - 1) * lineH;
        else if (pos.equals("center")) firstBaseline = H * 0.5f - totalH / 2f - fm.ascent;
        else firstBaseline = H * 0.12f - fm.ascent;
        float spaceW = paint.measureText(" ");
        for (int li = 0; li < lines.size(); li++) {
            int[] ln = lines.get(li);
            float lw = 0;
            for (int i = ln[0]; i < ln[1]; i++) lw += paint.measureText(words[i]) + (i > ln[0] ? spaceW : 0);
            float cx = (W - lw) / 2f;
            // v1.11: direction per LINE (same as the preview's per-chunk
            // check) — a mixed-language title no longer forces every
            // line LTR because the text starts with a Latin word.
            boolean rtlLine = isRtl(joinWords(words, ln[0], ln[1]));
            if (rtlLine) {
                // v1.10: same RTL fix as drawCaption — first logical word
                // at the RIGHT end of the line, advance right-to-left.
                float x = cx + lw;
                for (int i = ln[0]; i < ln[1]; i++) {
                    float w = paint.measureText(words[i]);
                    x -= w;
                    cv.drawText(words[i], x, firstBaseline + li * lineH, paint);
                    x -= spaceW;
                }
            } else {
                for (int i = ln[0]; i < ln[1]; i++) {
                    if (i > ln[0]) cx += spaceW;
                    cv.drawText(words[i], cx, firstBaseline + li * lineH, paint);
                    cx += paint.measureText(words[i]);
                }
            }
        }
    }

    private static String srtTime(double sec) {
        int ms = (int) Math.round(sec * 1000);
        int h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, r = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, r);
    }

    // ---------- EXPORT: fast trim/cut stream copy ----------
    private void doTrimExport(JSONObject spec, ExportTarget target, String progressCb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(currentVideoPath);
        int vTrack = -1, aTrack = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/") && vTrack < 0) vTrack = i;
            if (mime != null && mime.startsWith("audio/") && aTrack < 0) aTrack = i;
        }
        if (vTrack < 0) throw new Exception("No video track found");
        boolean mute = spec.optBoolean("mute", false);
        int rotate = spec.optInt("rotate", 0);
        JSONArray ranges = spec.optJSONArray("ranges");
        if (ranges == null || ranges.length() == 0) {
            ranges = new JSONArray();
            JSONObject r = new JSONObject();
            r.put("start", spec.optDouble("trimStart", 0));
            r.put("end", spec.optDouble("trimEnd", 0));
            ranges.put(r);
        }
        MediaMuxer muxer = new MediaMuxer(target.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (rotate != 0) muxer.setOrientationHint(rotate);
        int vOut = muxer.addTrack(ex.getTrackFormat(vTrack));
        int aOut = -1;
        if (aTrack >= 0 && !mute) aOut = muxer.addTrack(ex.getTrackFormat(aTrack));
        muxer.start();
        ByteBuffer buf = ByteBuffer.allocate(8 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long totalUs = 0;
        for (int ri = 0; ri < ranges.length(); ri++) {
            if (exportCancel) throw new Exception("cancelled");
            JSONObject r = ranges.getJSONObject(ri);
            long startUs = (long) (r.getDouble("start") * 1_000_000);
            long endUs = (long) (r.getDouble("end") * 1_000_000);
            long rangeBase = totalUs;
            // video
            ex.selectTrack(vTrack);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            while (true) {
                if (exportCancel) throw new Exception("cancelled");
                long t = ex.getSampleTime();
                if (t < 0 || t >= endUs) break;
                if (t >= startUs) {
                    int sz = ex.readSampleData(buf, 0);
                    if (sz < 0) break;
                    info.set(0, sz, t - startUs + rangeBase, ex.getSampleFlags());
                    muxer.writeSampleData(vOut, buf, info);
                }
                if (!ex.advance()) break;
            }
            ex.unselectTrack(vTrack);
            // audio
            if (aOut >= 0) {
                ex.selectTrack(aTrack);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                while (true) {
                    if (exportCancel) throw new Exception("cancelled");
                    long t = ex.getSampleTime();
                    if (t < 0 || t >= endUs) break;
                    if (t >= startUs) {
                        int sz = ex.readSampleData(buf, 0);
                        if (sz < 0) break;
                        info.set(0, sz, t - startUs + rangeBase, ex.getSampleFlags());
                        muxer.writeSampleData(aOut, buf, info);
                    }
                    if (!ex.advance()) break;
                }
                ex.unselectTrack(aTrack);
            }
            totalUs += (endUs - startUs);
            if (progressCb != null) {
                final int pct = (int) ((ri + 1) * 100.0 / ranges.length());
                jsCallback(progressCb, new JSONObject().put("pct", pct).put("msg", "Saving…").toString());
            }
        }
        muxer.stop(); muxer.release(); ex.release();
    }

    // ---------- EXPORT: burned-in captions (decode -> draw -> encode) ----------
    private void doBurnInExport(JSONObject spec, ExportTarget target, String progressCb) throws Exception {
        JSONArray ranges = spec.optJSONArray("ranges");
        if (ranges == null || ranges.length() == 0) {
            ranges = new JSONArray();
            JSONObject r = new JSONObject();
            r.put("start", spec.optDouble("trimStart", 0));
            r.put("end", spec.optDouble("trimEnd", 0));
            ranges.put(r);
        }
        double speed = spec.optDouble("speed", 1.0);
        if (speed < 0.5) speed = 0.5;
        if (speed > 2.0) speed = 2.0;
        boolean mute = spec.optBoolean("mute", false);
        int rotate = spec.optInt("rotate", 0);
        JSONArray captions = spec.optJSONArray("captions");
        JSONObject style = spec.optJSONObject("style");
        if (style == null) style = new JSONObject();
        JSONObject textOv = spec.optJSONObject("textOverlay");

        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(currentVideoPath);
        int vTrack = -1, aTrack = -1;
        MediaFormat vFmt = null, aFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/") && vTrack < 0) { vTrack = i; vFmt = f; }
            if (mime != null && mime.startsWith("audio/") && aTrack < 0) { aTrack = i; aFmt = f; }
        }
        if (vTrack < 0 || vFmt == null) throw new Exception("No video track found");
        String vMime = vFmt.getString(MediaFormat.KEY_MIME);
        int srcW = vFmt.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = vFmt.getInteger(MediaFormat.KEY_HEIGHT);
        int frameRate = 30;
        try { frameRate = vFmt.getInteger(MediaFormat.KEY_FRAME_RATE); } catch (Exception ignored) { }
        if (frameRate <= 0 || frameRate > 60) frameRate = 30;
        boolean swap = (rotate == 90 || rotate == 270);
        int outW = (swap ? srcH : srcW) & ~1;
        int outH = (swap ? srcW : srcH) & ~1;

        // total output duration (seconds) after trim/cut + speed
        double totalOutSec = 0;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject r = ranges.getJSONObject(i);
            totalOutSec += (r.getDouble("end") - r.getDouble("start")) / speed;
        }
        long totalFrames = Math.max(1, (long) (totalOutSec * frameRate));
        long frameDurUs = 1_000_000L / frameRate;

        // --- decode ---
        MediaCodec decoder = MediaCodec.createDecoderByType(vMime);
        ImageReader reader = ImageReader.newInstance(srcW, srcH, ImageFormat.YUV_420_888, 2);
        decoder.configure(vFmt, reader.getSurface(), null, 0);
        decoder.start();
        // --- encode ---
        MediaFormat encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        int bitrate = Math.max(1_000_000, (int) (outW * (long) outH * frameRate * 0.15));
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        File tmpEnc = new File(getCacheDir(), "enc_" + System.currentTimeMillis() + ".bin");
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmpEnc));
        MediaFormat encOutFmt = null;
        // v1.2: encoder output format, captured ONLY from the encoder.
        MediaFormat[] encFmtHolder = new MediaFormat[1];

        ex.selectTrack(vTrack);
        ByteBuffer decBuf = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean decEos = false, encEos = false;
        long nextOutUs = 0;
        long framesWritten = 0;
        long outBaseUs = 0; // output timeline offset per range
        boolean rangeStarted = false;

        for (int ri = 0; ri < ranges.length() && !exportCancel; ri++) {
            JSONObject r = ranges.getJSONObject(ri);
            long startUs = (long) (r.getDouble("start") * 1_000_000);
            long endUs = (long) (r.getDouble("end") * 1_000_000);
            // drain any pending image from previous range
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            boolean inputDone = false;
            boolean rangeEos = false;
            while (!rangeEos && !exportCancel) {
                // feed decoder input
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = decoder.getInputBuffer(inIdx);
                        long t = ex.getSampleTime();
                        if (t < 0 || t >= endUs) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int sz = ex.readSampleData(ib, 0);
                            if (sz < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sz, t, ex.getSampleFlags());
                                ex.advance();
                            }
                        }
                    }
                }
                // drain decoder output
                int outIdx = decoder.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoder.releaseOutputBuffer(outIdx, false);
                        rangeEos = true;
                        continue;
                    }
                    long srcPts = info.presentationTimeUs;
                    // get frame image (render to ImageReader surface)
                    decoder.releaseOutputBuffer(outIdx, true);
                    Image img = null;
                    for (int tries = 0; tries < 50 && img == null; tries++) {
                        img = reader.acquireLatestImage();
                        if (img == null) { try { Thread.sleep(5); } catch (Exception ignored) { } }
                    }
                    if (img == null) continue;
                    Bitmap bmp;
                    try { bmp = imageToBitmap(img); } finally { img.close(); }
                    if (srcPts < startUs) continue;
                    if (srcPts >= endUs) continue;
                    long scaledUs = outBaseUs + (long) ((srcPts - startUs) / speed);
                    // draw captions + text overlay for this frame time
                    // v1.1 FIX: Canvas needs a MUTABLE bitmap — defensive copy
                    // even though imageToBitmap() now returns a mutable one.
                    if (!bmp.isMutable()) {
                        Bitmap m = bmp.copy(Bitmap.Config.ARGB_8888, true);
                        bmp.recycle();
                        bmp = m;
                    }
                    Canvas cv = new Canvas(bmp);
                    double nowSec = scaledUs / 1_000_000.0;
                    if (textOv != null) drawTextOverlay(cv, bmp.getWidth(), bmp.getHeight(), textOv);
                    if (captions != null) {
                        for (int ci = 0; ci < captions.length(); ci++) {
                            JSONObject cap = captions.getJSONObject(ci);
                            double cs = cap.getDouble("start"), ce = cap.getDouble("end");
                            // captions are in original timeline; map through ranges/speed
                            double mappedStart = mapTime(cs, ranges, speed);
                            double mappedEnd = mapTime(ce, ranges, speed);
                            if (mappedStart < 0 || mappedEnd < 0) continue;
                            if (nowSec >= mappedStart && nowSec < mappedEnd) {
                                drawCaption(cv, bmp.getWidth(), bmp.getHeight(),
                                        cap.optString("text", ""), style, mappedStart, mappedEnd, nowSec);
                                break;
                            }
                        }
                    }
                    // rotate
                    Bitmap work = bmp;
                    if (rotate != 0) {
                        Matrix m = new Matrix();
                        m.postRotate(rotate);
                        work = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        if (work != bmp) bmp.recycle();
                    }
                    if (work.getWidth() != outW || work.getHeight() != outH) {
                        Bitmap scaled = Bitmap.createScaledBitmap(work, outW, outH, true);
                        if (scaled != work) work.recycle();
                        work = scaled;
                    }
                    byte[] nv12 = bitmapToNv12(work, outW, outH);
                    work.recycle();
                    // emit frame(s) at output slots up to scaledUs
                    while (nextOutUs <= scaledUs && framesWritten < totalFrames) {
                        int eIn = encoder.dequeueInputBuffer(10000);
                        if (eIn < 0) break;
                        ByteBuffer eb = encoder.getInputBuffer(eIn);
                        eb.clear();
                        eb.put(nv12);
                        encoder.queueInputBuffer(eIn, 0, nv12.length, nextOutUs, 0);
                        nextOutUs += frameDurUs;
                        framesWritten++;
                        // drain encoder (also captures the ENCODER's output
                        // format on INFO_OUTPUT_FORMAT_CHANGED — v1.2 FIX)
                        drainEncoder(encoder, dos, encFmtHolder);
                        if (encOutFmt == null && encFmtHolder[0] != null) encOutFmt = encFmtHolder[0];
                    }
                    if (progressCb != null && totalFrames > 0) {
                        final int pct = (int) Math.min(90, framesWritten * 90 / totalFrames);
                        jsCallback(progressCb, new JSONObject().put("pct", pct).put("msg", "Burning captions…").toString());
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // v1.2 FIX: NEVER store the decoder's format here. The
                    // decoder outputs RAW video; MediaMuxer.addTrack() with a
                    // raw format throws "Failed to add the track to the muxer"
                    // (the v1.1 export failure). The encoder's format is the
                    // only one the muxer accepts — it is captured from the
                    // ENCODER in drainEncoder() and the flush loop below.
                }
            }
            outBaseUs += (long) ((endUs - startUs) / speed);
            rangeStarted = true;
        }
        // flush encoder
        if (!exportCancel) {
            int eIn = encoder.dequeueInputBuffer(10000);
            if (eIn >= 0) encoder.queueInputBuffer(eIn, 0, 0, nextOutUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            long deadline = System.currentTimeMillis() + 15000;
            while (!encEos && System.currentTimeMillis() < deadline) {
                int outIdx = encoder.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if (encOutFmt == null) {
                        try { encOutFmt = encoder.getOutputFormat(); } catch (Exception ignored) { }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEos = true;
                    else if (info.size > 0) {
                        ByteBuffer ob = encoder.getOutputBuffer(outIdx);
                        byte[] data = new byte[info.size];
                        ob.position(info.offset); ob.get(data);
                        dos.writeInt(data.length);
                        dos.writeLong(info.presentationTimeUs);
                        dos.writeInt(info.flags);
                        dos.write(data);
                        if (encOutFmt == null) {
                            try { encOutFmt = encoder.getOutputFormat(); } catch (Exception ignored) { }
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encOutFmt = encoder.getOutputFormat();
                }
            }
        }
        dos.close();
        try { decoder.stop(); decoder.release(); } catch (Exception ignored) { }
        try { encoder.stop(); encoder.release(); } catch (Exception ignored) { }
        try { reader.close(); } catch (Exception ignored) { }
        ex.release();
        if (exportCancel) { tmpEnc.delete(); throw new Exception("cancelled"); }
        if (encOutFmt == null) { tmpEnc.delete(); throw new Exception("Encoder produced no output"); }

        // --- mux: video from temp file + audio re-extracted with mapped timestamps ---
        // v1.2 FIX: validate the encoder format BEFORE addTrack. A raw format
        // (or one without csd-0 SPS/PPS) makes the native muxer throw
        // "Failed to add the track to the muxer".
        String encMime = encOutFmt.getString(MediaFormat.KEY_MIME);
        if (encMime == null || !encMime.startsWith("video/") || !encOutFmt.containsKey("csd-0")) {
            tmpEnc.delete();
            throw new Exception("Bad encoder format for muxer (mime=" + encMime + ", no csd-0) — burn-in video could not be saved");
        }
        MediaMuxer muxer = new MediaMuxer(target.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vOut = muxer.addTrack(encOutFmt);
        int aOut = -1;
        MediaExtractor aex = null;
        if (aTrack >= 0 && !mute && aFmt != null) {
            String aMime = aFmt.getString(MediaFormat.KEY_MIME);
            if (aMime != null && aMime.startsWith("audio/")) {
                try { aOut = muxer.addTrack(aFmt); }
                catch (Exception ae) { aOut = -1; } // video-only export still succeeds
            }
        }
        muxer.start();
        DataInputStream dis = new DataInputStream(new FileInputStream(tmpEnc));
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo winfo = new MediaCodec.BufferInfo();
        try {
            while (dis.available() > 0) {
                int len = dis.readInt();
                long pts = dis.readLong();
                int flags = dis.readInt();
                byte[] data = new byte[len];
                dis.readFully(data);
                buf.clear();
                buf.put(data);
                buf.flip();
                winfo.set(0, len, pts, flags);
                muxer.writeSampleData(vOut, buf, winfo);
            }
        } catch (Exception ignored) { }
        dis.close();
        tmpEnc.delete();
        if (aOut >= 0) {
            aex = new MediaExtractor();
            aex.setDataSource(currentVideoPath);
            aex.selectTrack(aTrack);
            long audioBase = 0;
            for (int ri = 0; ri < ranges.length(); ri++) {
                JSONObject r = ranges.getJSONObject(ri);
                long startUs = (long) (r.getDouble("start") * 1_000_000);
                long endUs = (long) (r.getDouble("end") * 1_000_000);
                aex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                while (true) {
                    if (exportCancel) break;
                    long t = aex.getSampleTime();
                    if (t < 0 || t >= endUs) break;
                    if (t >= startUs) {
                        int sz = aex.readSampleData(buf, 0);
                        if (sz < 0) break;
                        long outPts = audioBase + (long) ((t - startUs) / speed);
                        winfo.set(0, sz, outPts, aex.getSampleFlags());
                        muxer.writeSampleData(aOut, buf, winfo);
                    }
                    if (!aex.advance()) break;
                }
                audioBase += (long) ((endUs - startUs) / speed);
            }
            aex.release();
        }
        muxer.stop(); muxer.release();
        if (progressCb != null) jsCallback(progressCb, new JSONObject().put("pct", 100).put("msg", "Done").toString());
    }

    // map an original-timeline second to output timeline seconds (after trim/cut+speed); -1 if cut out
    private static double mapTime(double t, JSONArray ranges, double speed) throws Exception {
        double out = 0;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject r = ranges.getJSONObject(i);
            double s = r.getDouble("start"), e = r.getDouble("end");
            if (t >= s && t < e) return out + (t - s) / speed;
            out += (e - s) / speed;
        }
        return -1;
    }

    // v1.2 FIX: the encoder's output format must be captured from the ENCODER
    // on INFO_OUTPUT_FORMAT_CHANGED. The old code swallowed that signal here
    // (outIdx < 0 -> return) and the burn-in loop stored the DECODER's raw
    // format instead — which made MediaMuxer.addTrack() throw
    // "Failed to add the track to the muxer" (the v1.1 export failure).
    private void drainEncoder(MediaCodec encoder, DataOutputStream dos, MediaFormat[] fmtHolder) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIdx = encoder.dequeueOutputBuffer(info, 0);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try { fmtHolder[0] = encoder.getOutputFormat(); } catch (Exception ignored) { }
                continue;
            }
            if (outIdx < 0) return;
            if (info.size > 0) {
                ByteBuffer ob = encoder.getOutputBuffer(outIdx);
                byte[] data = new byte[info.size];
                ob.position(info.offset); ob.get(data);
                dos.writeInt(data.length);
                dos.writeLong(info.presentationTimeUs);
                dos.writeInt(info.flags);
                dos.write(data);
            }
            encoder.releaseOutputBuffer(outIdx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    // ---------- Gemini transcription ----------
    private String extractAudioFile() throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(currentVideoPath);
        int aTrack = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { aTrack = i; break; }
        }
        if (aTrack < 0) throw new Exception("No audio track in this video");
        File out = new File(getCacheDir(), "audio_" + System.currentTimeMillis() + ".m4a");
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int aOut = muxer.addTrack(ex.getTrackFormat(aTrack));
        muxer.start();
        ex.selectTrack(aTrack);
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.set(0, sz, ex.getSampleTime(), ex.getSampleFlags());
            muxer.writeSampleData(aOut, buf, info);
            ex.advance();
        }
        muxer.stop(); muxer.release(); ex.release();
        return out.getAbsolutePath();
    }

    private String geminiTranscribe(String key, String model, String lang, boolean emojis) throws Exception {
        String audioPath = extractAudioFile();
        byte[] audio = readAll(new FileInputStream(audioPath));
        new File(audioPath).delete();
        if (audio.length > 18 * 1024 * 1024) {
            throw new Exception("Audio too large for one pass (" + (audio.length / 1048576) + " MB). Trim the video shorter and try again.");
        }
        // v1.3: "auto" = detect the spoken language first, then transcribe in
        // that language (Urdu script for Urdu, Latin letters for Roman Urdu,
        // English for English). Fixed languages keep the old behaviour.
        // v1.10: spoken Urdu and Hindi sound the SAME, so Gemini on
        // Auto-detect kept guessing Hindi and writing Devanagari (his
        // screenshot showed "साहब मेरे"). Rule now: Urdu OR Hindi speech is
        // ALWAYS written in Urdu script (Nastaliq) — NEVER Devanagari.
        String prompt;
        if ("auto".equals(lang)) {
            prompt = "First detect the spoken language in this audio. Then transcribe it word-for-word "
                    + "in the detected language. IMPORTANT: spoken Urdu and Hindi sound identical — "
                    + "if the speech is Urdu OR Hindi, ALWAYS write it in Urdu script (Nastaliq, اردو). "
                    + "NEVER write in Devanagari (Hindi) script. "
                    + "If Roman Urdu (Urdu spoken in Latin letters), write in Latin letters; "
                    + "if English, write in English. ";
        } else if ("ur".equals(lang)) {
            prompt = "Transcribe this audio word-for-word in Urdu (Urdu script, Nastaliq). "
                    + "Write ONLY in Urdu script — never in Devanagari (Hindi) script, "
                    + "even though spoken Urdu and Hindi sound the same. ";
        } else {
            String langName = lang.equals("en") ? "English" : "Roman Urdu (Urdu spoken, write in Latin letters)";
            prompt = "Transcribe this audio word-for-word in " + langName + ". ";
        }
        prompt += "Return ONLY a JSON array, nothing else, no markdown: "
                + "[{\"start\":0.0,\"end\":2.5,\"text\":\"...\"}] with start/end in seconds, "
                + "one segment per sentence or short phrase (2-6 seconds each). "
                + "Keep the exact spoken words, do not summarise.";
        // v1.3: "Include emojis & styling" ON = one relevant emoji at the
        // start of each segment; OFF = no emojis at all.
        if (emojis) {
            prompt += " Start each segment's text with ONE relevant emoji that matches the segment's meaning "
                    + "(for example \uD83D\uDE0A for happy, \uD83D\uDE22 for sad, \uD83D\uDD25 for exciting). "
                    + "Never more than one emoji per segment.";
        } else {
            prompt += " Do not include any emojis.";
        }
        JSONObject part1 = new JSONObject();
        JSONObject inline = new JSONObject();
        inline.put("mime_type", "audio/mp4");
        inline.put("data", Base64.encodeToString(audio, Base64.NO_WRAP));
        part1.put("inline_data", inline);
        JSONObject part2 = new JSONObject();
        part2.put("text", prompt);
        JSONArray parts = new JSONArray();
        parts.put(part1); parts.put(part2);
        JSONObject content = new JSONObject();
        content.put("parts", parts);
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        contents.put(content);
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject().put("temperature", 0.2));

        // v1.10: model busy (503/429) is Google-side and temporary — retry
        // the same model twice (5s apart), then fall back to other models,
        // before giving up. The user's saved model is NEVER overwritten;
        // the fallback is only for this one attempt.
        String[] models = new String[]{ model, "gemini-2.0-flash", "gemini-2.5-flash-lite" };
        java.util.List<String> tried = new java.util.ArrayList<String>();
        int lastCode = 0;
        for (String m : models) {
            if (m == null || m.isEmpty() || tried.contains(m)) continue;
            tried.add(m);
            boolean retryable = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return geminiCall(key, m, body);
                } catch (GeminiHttpException e) {
                    lastCode = e.code;
                    retryable = (e.code == 503 || e.code == 429);
                    if (retryable && attempt < 2) {
                        try { Thread.sleep(5000); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new Exception("Cancelled"); }
                        continue;
                    }
                    break;
                }
            }
            if (!retryable) break; // 400/401/403: another model will not help
        }
        throw new Exception(friendlyGeminiError(lastCode));
    }

    // HTTP error carrying its status code, so the caller can decide:
    // retry + fall back on 503/429, give up at once on 400/401/403.
    private static class GeminiHttpException extends Exception {
        final int code;
        GeminiHttpException(int code, String msg) { super(msg); this.code = code; }
    }

    // v1.10: short Roman Urdu messages — raw JSON is never shown to the user.
    private static String friendlyGeminiError(int code) {
        if (code == 503) return "Gemini model busy hai, thodi der baad dobara try karo";
        if (code == 429) return "Limit khatam, thodi der baad try karo";
        if (code == 401 || code == 400) return "API key theek nahi, Settings mein check karo";
        if (code > 0) return "Caption nahi ban sake (HTTP " + code + "), dobara try karo";
        return "Caption nahi ban sake, dobara try karo";
    }

    // One Gemini request with the given model. Throws GeminiHttpException
    // (carrying the HTTP code) on any non-200, so the caller can retry
    // 503/429 and fall back to another model.
    private String geminiCall(String key, String model, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(300000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        java.io.InputStream es = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = es == null ? "" : new String(readAll(es), "UTF-8");
        c.disconnect();
        if (code != 200) throw new GeminiHttpException(code, "Gemini HTTP " + code);
        JSONObject j = new JSONObject(resp);
        String text = j.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
        int a = text.indexOf('['), b = text.lastIndexOf(']');
        if (a < 0 || b < 0) throw new Exception("Gemini did not return caption JSON");
        JSONArray segs = new JSONArray(text.substring(a, b + 1));
        // normalise
        JSONArray out = new JSONArray();
        for (int i = 0; i < segs.length(); i++) {
            JSONObject s = segs.getJSONObject(i);
            JSONObject n = new JSONObject();
            n.put("start", s.optDouble("start", 0));
            n.put("end", s.optDouble("end", 0));
            n.put("text", s.optString("text", ""));
            if (!n.optString("text").isEmpty() && n.optDouble("end") > n.optDouble("start")) out.put(n);
        }
        return out.toString();
    }

    // ================= v1.14: GitHub Releases update check =================
    // Auto-check (manual=false): at most once a day, fully silent unless a
    // newer version is found — offline, no repo yet, or API error all mean
    // nothing happens. Manual check (from Settings): always runs and toasts
    // the result, so the user gets an answer either way.
    private String currentVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) { return "0"; }
    }

    // Numeric compare, "v1.14" vs "1.13" -> +1. Ignores a leading 'v' and
    // any suffix after '-' (e.g. "1.14-beta").
    private static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("^[vV]", "").split("-")[0].split("\\.");
        String[] pb = b.replaceAll("^[vV]", "").split("-")[0].split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseVerPart(pa[i]) : 0;
            int y = i < pb.length ? parseVerPart(pb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int parseVerPart(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private void checkForUpdate(final boolean manual) {
        pool.execute(() -> {
            try {
                SharedPreferences p = getSharedPreferences("captioncut", MODE_PRIVATE);
                long now = System.currentTimeMillis();
                if (!manual && now - p.getLong("update_last_check", 0) < 24L * 3600 * 1000) return;
                p.edit().putLong("update_last_check", now).apply();
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "CaptionCut");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) { if (manual) toast("Update check nahi ho saka — internet check karo"); return; }
                JSONObject rel = new JSONObject(readAllText(c.getInputStream()));
                String tag = rel.optString("tag_name", "");
                if (tag.isEmpty()) { if (manual) toast("Update check nahi ho saka"); return; }
                String dl = rel.optString("html_url", ""); // fallback: release page
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) { dl = a.optString("browser_download_url", dl); break; }
                    }
                }
                if (compareVersions(tag, currentVersionName()) > 0 && !dl.isEmpty()) {
                    final String v = tag, u = dl;
                    runOnUiThread(() -> {
                        try { wv.evaluateJavascript("window.showUpdateBanner(" + JSONObject.quote(v) + "," + JSONObject.quote(u) + ")", null); }
                        catch (Exception ignored) { }
                    });
                } else if (manual) {
                    toast("Latest version hai ✔");
                }
            } catch (Exception e) {
                // Silent on auto-check (offline / no repo yet / API down).
                if (manual) toast("Update check nahi ho saka — internet check karo");
            }
        });
    }

    // v1.15: one-tap in-app update — DownloadManager downloads the APK
    // inside the app (NO browser); when it finishes, the install pop-up
    // appears in front. Re-tapping Update installs the already-downloaded
    // APK instead of downloading again. Same keystore = installs over the
    // old version, no uninstall.
    private static final String UPDATE_APK_NAME = "CaptionCut-update.apk";

    private void downloadUpdateApk(final String url) {
        runOnUiThread(() -> {
            try {
                if (url == null || url.isEmpty()) { toast("Update link nahi mila"); return; }
                // Release-page fallback (no .apk asset): open in the browser.
                if (!url.toLowerCase().endsWith(".apk")) { openUrlInBrowser(url); return; }
                SharedPreferences p = getSharedPreferences("captioncut", MODE_PRIVATE);
                long savedId = p.getLong("update_download_id", -1);
                String savedUrl = p.getString("update_download_url", "");
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) { openUrlInBrowser(url); return; }
                if (savedId != -1 && url.equals(savedUrl)) {
                    int st = queryDownloadStatus(dm, savedId);
                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        updateDownloadId = savedId; updateDownloadUrl = url;
                        installDownloadedApk(); return;
                    }
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PAUSED
                            || st == DownloadManager.STATUS_PENDING) {
                        toast("Download ho raha hai — thodi der rukho"); return;
                    }
                    // FAILED or unknown: fall through and re-download.
                }
                File dest = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_APK_NAME);
                try { if (dest.exists()) dest.delete(); } catch (Exception ignored) { }
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setTitle("CaptionCut Update");
                req.setDescription("Naya version download ho raha hai…");
                req.setMimeType("application/vnd.android.package-archive");
                req.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_NAME);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setAllowedOverMetered(true);
                req.setAllowedOverRoaming(false);
                long id = dm.enqueue(req);
                updateDownloadId = id;
                updateDownloadUrl = url;
                p.edit().putLong("update_download_id", id).putString("update_download_url", url).apply();
                registerUpdateReceiver();
                toast("Download shuru ho gaya ✔");
            } catch (Exception e) {
                openUrlInBrowser(url);
            }
        });
    }

    private int queryDownloadStatus(DownloadManager dm, long id) {
        Cursor c = null;
        try {
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                return c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception ignored) { }
        finally { if (c != null) c.close(); }
        return -1;
    }

    private void registerUpdateReceiver() {
        if (updateReceiverRegistered) return;
        updateDownloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != updateDownloadId) return;
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                int st = dm == null ? -1 : queryDownloadStatus(dm, id);
                unregisterUpdateReceiver();
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    installDownloadedApk();
                } else {
                    toast("Download nahi ho saka — internet check kar ke dobara try karo");
                    openUrlInBrowser(updateDownloadUrl);
                }
            }
        };
        try {
            registerReceiver(updateDownloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            updateReceiverRegistered = true;
        } catch (Exception ignored) { }
    }

    private void unregisterUpdateReceiver() {
        if (!updateReceiverRegistered) return;
        try { unregisterReceiver(updateDownloadReceiver); } catch (Exception ignored) { }
        updateReceiverRegistered = false;
        updateDownloadReceiver = null;
    }

    @Override
    protected void onDestroy() {
        unregisterUpdateReceiver();
        super.onDestroy();
    }

    private void installDownloadedApk() {
        runOnUiThread(() -> {
            try {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(s);
                    toast("Pehle 'Allow from this source' ON karo, phir dobara Update dabao");
                    return;
                }
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Uri uri = dm == null ? null : dm.getUriForDownloadedFile(updateDownloadId);
                if (uri == null) { openUrlInBrowser(updateDownloadUrl); return; }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) {
                openUrlInBrowser(updateDownloadUrl);
            }
        });
    }

    private void openUrlInBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("Cannot open link"); }
    }

    private static String readAllText(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ================= JS Bridge =================
    class Bridge {

        private SharedPreferences prefs() {
            return getSharedPreferences("captioncut", MODE_PRIVATE);
        }

        @JavascriptInterface
        public void toast(String msg) { MainActivity.this.toast(msg); }

        @JavascriptInterface
        public void pickVideo() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("video/*");
                    startActivityForResult(i, PICK_VIDEO);
                } catch (Exception e) {
                    showImportError("Could not open gallery: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public String getCurrentVideo() {
            try {
                if (currentVideoPath.isEmpty()) return "";
                return videoInfoJson(currentVideoPath, currentVideoName).toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String getRecent() {
            return prefs().getString("recent", "[]");
        }

        @JavascriptInterface
        public void loadRecent(String path, String name) {
            File f = new File(path);
            if (!f.exists()) { showImportError("File not found — import the video again"); return; }
            currentVideoPath = path;
            currentVideoName = name;
            try {
                final String js = "window.__onVideo(" + JSONObject.quote(videoInfoJson(path, name).toString()) + ");";
                wv.post(() -> wv.evaluateJavascript(js, null));
            } catch (Exception e) { showImportError("Could not open video: " + e.getMessage()); }
        }

        // ---- settings ----
        @JavascriptInterface public String getKey() { return prefs().getString("gemini_key", ""); }
        @JavascriptInterface public void setKey(String k) {
            prefs().edit().putString("gemini_key", k.trim()).apply(); toast("API key saved ✔");
        }
        @JavascriptInterface public String getModelId() { return prefs().getString("gemini_model", "gemini-2.5-flash"); }
        @JavascriptInterface public void setModelId(String id) {
            prefs().edit().putString("gemini_model", id.trim()).apply(); toast("Model updated ✔");
        }
        @JavascriptInterface public String getLang() { return prefs().getString("lang", "ur"); }
        @JavascriptInterface public void setLang(String l) { prefs().edit().putString("lang", l).apply(); }
        @JavascriptInterface public String getStyle() { return prefs().getString("style", ""); }
        @JavascriptInterface public void setStyle(String s) { prefs().edit().putString("style", s).apply(); }

        @JavascriptInterface
        public void testKeyAsync(final String key, final String model, final String cb) {
            pool.execute(() -> {
                String out;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://generativelanguage.googleapis.com/v1beta/models/" + model
                            + ":generateContent").openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("x-goog-api-key", key);
                    String body = "{\"contents\":[{\"parts\":[{\"text\":\"Say OK\"}]}],\"generationConfig\":{\"maxOutputTokens\":5}}";
                    OutputStream os = c.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                    int code = c.getResponseCode();
                    String resp = new String(readAll(code < 400 ? c.getInputStream() : c.getErrorStream()), "UTF-8");
                    c.disconnect();
                    out = code == 200 ? "OK" : "ERROR:HTTP " + code + " " + resp.substring(0, Math.min(200, resp.length()));
                } catch (Exception e) {
                    out = "ERROR:" + e.getMessage();
                }
                jsCallback(cb, out);
            });
        }

        @JavascriptInterface
        public void transcribeAsync(final String lang, final boolean emojis, final String cb) {
            pool.execute(() -> {
                String out;
                try {
                    if (currentVideoPath.isEmpty()) { jsCallback(cb, "ERROR:No video imported yet"); return; }
                    String key = prefs().getString("gemini_key", "");
                    if (key.isEmpty()) { jsCallback(cb, "ERROR:No Gemini API key — add your free key in Settings first"); return; }
                    String model = prefs().getString("gemini_model", "gemini-2.5-flash");
                    out = geminiTranscribe(key, model, lang, emojis);
                } catch (Exception e) {
                    Log.e(TAG, "transcribe failed", e);
                    out = "ERROR:" + e.getMessage();
                }
                jsCallback(cb, out);
            });
        }

        // Writes the .SRT file for the spec's captions; returns the file name.
        private String writeSrtFile(JSONObject spec) throws Exception {
            JSONArray caps = spec.optJSONArray("captions");
            if (caps == null || caps.length() == 0) throw new Exception("No captions to export");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < caps.length(); i++) {
                JSONObject c = caps.getJSONObject(i);
                sb.append(i + 1).append("\n");
                sb.append(srtTime(c.getDouble("start"))).append(" --> ").append(srtTime(c.getDouble("end"))).append("\n");
                sb.append(c.optString("text", "")).append("\n\n");
            }
            String name = "CaptionCut_" + System.currentTimeMillis() + ".srt";
            ExportTarget t = openExportTarget(name, "application/x-subrip");
            OutputStream os = new FileOutputStream(t.pfd.getFileDescriptor());
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
            closeExportTarget(t);
            return name;
        }

        @JavascriptInterface
        public void exportVideoAsync(final String specJson, final String cb, final String progressCb) {
            if (exportRunning) { jsCallback(cb, "{\"error\":\"Export already running\"}"); return; }
            exportRunning = true;
            exportCancel = false;
            pool.execute(() -> {
                String out;
                ExportTarget target = null;
                try {
                    if (currentVideoPath.isEmpty()) throw new Exception("No video imported yet");
                    JSONObject spec = new JSONObject(specJson);
                    boolean burnIn = spec.optBoolean("burnIn", true);
                    boolean hasCaptions = spec.optJSONArray("captions") != null && spec.optJSONArray("captions").length() > 0;
                    boolean hasText = spec.optJSONObject("textOverlay") != null
                            && !spec.optJSONObject("textOverlay").optString("text", "").isEmpty();
                    boolean needsEncode = (burnIn && (hasCaptions || hasText)) || spec.optDouble("speed", 1.0) != 1.0;
                    String name = "CaptionCut_" + System.currentTimeMillis() + ".mp4";
                    target = openExportTarget(name, "video/mp4");
                    boolean usedFallback = false;
                    String fallbackReason = null;
                    String srtName = null;
                    if (needsEncode) {
                        try {
                            doBurnInExport(spec, target, progressCb);
                        } catch (Exception be) {
                            // v1.2 SAFETY FALLBACK: never leave the user with
                            // nothing. If burn-in fails for any reason, save
                            // the plain trimmed video + SRT instead and say so.
                            if (exportCancel) throw be;
                            Log.e(TAG, "burn-in failed, falling back to plain trim", be);
                            fallbackReason = be.getMessage();
                            try { if (target.pfd != null) target.pfd.close(); } catch (Exception ignored) { }
                            try { if (target.uri != null) getContentResolver().delete(target.uri, null, null); } catch (Exception ignored) { }
                            target = openExportTarget(name, "video/mp4");
                            doTrimExport(spec, target, progressCb);
                            usedFallback = true;
                            try { srtName = writeSrtFile(spec); } catch (Exception ignored) { }
                        }
                    } else {
                        doTrimExport(spec, target, progressCb);
                    }
                    closeExportTarget(target);
                    JSONObject r = new JSONObject();
                    r.put("ok", true);
                    r.put("name", name);
                    r.put("path", target.path);
                    r.put("uri", target.uri != null ? target.uri.toString() : "");
                    r.put("burnIn", needsEncode && !usedFallback);
                    r.put("fallback", usedFallback);
                    if (fallbackReason != null) r.put("fallbackReason", fallbackReason);
                    if (srtName != null) r.put("srtName", srtName);
                    out = r.toString();
                } catch (Exception e) {
                    Log.e(TAG, "export failed", e);
                    if (target != null) {
                        try { if (target.pfd != null) target.pfd.close(); } catch (Exception ignored) { }
                        try { if (target.uri != null) getContentResolver().delete(target.uri, null, null); } catch (Exception ignored) { }
                    }
                    try {
                        JSONObject r = new JSONObject();
                        r.put("error", e.getMessage());
                        out = r.toString();
                    } catch (Exception je) { out = "{\"error\":\"Export failed\"}"; }
                } finally {
                    exportRunning = false;
                }
                jsCallback(cb, out);
            });
        }

        @JavascriptInterface
        public void exportSrtAsync(final String specJson, final String cb) {
            pool.execute(() -> {
                String out;
                try {
                    JSONObject spec = new JSONObject(specJson);
                    String name = writeSrtFile(spec);
                    JSONObject r = new JSONObject();
                    r.put("ok", true);
                    r.put("name", name);
                    out = r.toString();
                } catch (Exception e) {
                    try { out = new JSONObject().put("error", e.getMessage()).toString(); }
                    catch (Exception je) { out = "{\"error\":\"SRT export failed\"}"; }
                }
                jsCallback(cb, out);
            });
        }

        @JavascriptInterface
        public void cancelExport() { exportCancel = true; }

        @JavascriptInterface
        public void shareLast() {
            runOnUiThread(() -> {
                try {
                    if (lastExportUri.isEmpty()) { toast("Nothing exported yet"); return; }
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("video/mp4");
                    i.putExtra(Intent.EXTRA_STREAM, Uri.parse(lastExportUri));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share video"));
                } catch (Exception e) { toast("Share failed"); }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> MainActivity.this.openUrlInBrowser(url));
        }

        // v1.15: Update button — download inside the app (no browser);
        // the install pop-up appears when the download finishes.
        @JavascriptInterface
        public void downloadUpdate(String url) { downloadUpdateApk(url); }

        // v1.14: manual update check from Settings — always answers with
        // a banner (if newer) or a toast (latest / failed).
        @JavascriptInterface
        public void checkUpdateNow() { checkForUpdate(true); }
    }
}
