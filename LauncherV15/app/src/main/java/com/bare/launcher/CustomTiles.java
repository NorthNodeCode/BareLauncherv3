package com.bare.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Private storage for user-picked app tiles ("Change icon", v2.3.0).
 *
 * <p>When the user picks an image we copy it here immediately instead of
 * keeping a persistable {@code content://} grant: the picked document can
 * move, be deleted, or live on a USB stick that is unplugged later, and some
 * TV pickers ({@code ACTION_GET_CONTENT} file managers) cannot grant
 * persistable access at all. One small WEBP per customised app, at
 * {@code filesDir/tiles/}{@link Customizations#tileFileName}.
 *
 * <p>The stored copy is resolution-independent — its long side is capped at
 * {@link #MAX_SRC_PX}, enough for a 4K tile — so a density or display change
 * needs no invalidation here; the banner pipeline re-renders the tile at the
 * new size from the same file.
 *
 * <p>Every method does blocking I/O and must run off the UI thread (the
 * activity uses its icon executor). Writes are tmp + rename, so a reader on
 * another worker sees either the old file or the new one, never a partial
 * one. All failures return {@code false} / {@code null}; nothing throws.
 */
final class CustomTiles {

    /** Long-side cap of the stored copy. A tile is ~1/6 of the screen width
     *  (≈ 300 px at 1080p, ≈ 600 px at 4K), so 640 px is never upscaled on a
     *  TV yet keeps the file at a few tens of KB and the decode cheap. */
    static final int MAX_SRC_PX = 640;

    /** WEBP quality. Legacy {@link Bitmap.CompressFormat#WEBP} is the only
     *  WEBP enum on minSdk 26 (see the QUALITY note in {@link IconDiskCache});
     *  90 keeps photos clean while staying in lossy mode, and the encoder
     *  keeps an alpha channel for transparent logos. */
    private static final int QUALITY = 90;

    private static final String DIR_NAME = "tiles";
    private static final String TMP_EXT  = ".tmp";

    private final File dir;

    CustomTiles(Context ctx) {
        dir = new File(ctx.getFilesDir(), DIR_NAME);
    }

    /**
     * Decode the picked image at {@code uri}, shrink it to at most
     * {@link #MAX_SRC_PX} on its long side, and store it as the tile for
     * {@code key}, replacing any previous one. Two passes over the stream
     * (bounds, then a sub-sampled decode) keep peak memory near the output
     * size even for a 50-megapixel photo.
     *
     * @return {@code true} once the new file is in place.
     */
    boolean save(ContentResolver cr, Uri uri, String key) {
        String name = Customizations.tileFileName(key);
        if (cr == null || uri == null || name == null) return false;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return false;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sw = bounds.outWidth, sh = bounds.outHeight;
            if (sw <= 0 || sh <= 0) return false;            // not an image we can decode
            float scale = Math.min(1f, (float) MAX_SRC_PX / Math.max(sw, sh));
            int tw = Math.max(1, Math.round(sw * scale));
            int th = Math.max(1, Math.round(sh * scale));

            BitmapFactory.Options opts = new BitmapFactory.Options();
            // Largest power-of-two step that still decodes at or above the
            // target on both axes, so the final scale below only ever shrinks.
            opts.inSampleSize = WallpaperController.computeSampleSizeAtLeast(sw, sh, tw, th);
            // Software config: the tile renderer draws this into a software
            // Canvas, which cannot read a HARDWARE bitmap.
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return false;
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return false;
            if (bmp.getWidth() > tw || bmp.getHeight() > th) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
                if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
            }
            return write(name, bmp);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // SecurityException (grant revoked), FileNotFoundException,
            // a corrupt image, or OOM on a low-RAM box: report failure and
            // leave any previous tile untouched.
            return false;
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }

    private boolean write(String name, Bitmap bmp) {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false;
        File tmp  = new File(dir, name + TMP_EXT);
        File dest = new File(dir, name);
        boolean wrote;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            //noinspection deprecation -- legacy WEBP is the only WEBP enum on minSdk 26.
            wrote = bmp.compress(Bitmap.CompressFormat.WEBP, QUALITY, out);
        } catch (IOException | OutOfMemoryError e) {
            wrote = false;
        }
        if (wrote && tmp.renameTo(dest)) return true;
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return false;
    }

    /** Decode the stored tile for {@code key}, or {@code null} if there is
     *  none or it cannot be read (the caller then falls back to the app's own
     *  banner). Always a software ARGB_8888 bitmap — see {@link #save}. */
    Bitmap load(String key) {
        String name = Customizations.tileFileName(key);
        if (name == null) return null;
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(f.getPath(), opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Delete the stored tile for {@code key} (and any leftover tmp file).
     *  Best-effort: a file that survives is harmless, because the activity
     *  only reads tiles for keys in its custom-tile set. */
    void delete(String key) {
        String name = Customizations.tileFileName(key);
        if (name == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir, name).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, name + TMP_EXT).delete();
    }
}
