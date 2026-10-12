package com.bare.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java helpers for the per-app customisations added in v2.3.0:
 * <b>Rename</b> (a user label shown instead of the app's own) and
 * <b>Change icon</b> (a user image shown instead of the app's banner tile).
 *
 * <p>Three pieces live here, all Android-free so they are exercised by fast
 * JVM unit tests (see {@code CustomizationsTest}), mirroring
 * {@link KeymapStore} / {@link AppOrder}:
 * <ol>
 *   <li>{@link #normalizeLabel} — the single definition of what a stored
 *       label may look like (trimmed, whitespace collapsed, capped).</li>
 *   <li>{@link #parseRenames} / {@link #serializeRenames} — the persisted
 *       rename map, one {@code SharedPreferences} string of
 *       {@code key=label,key=label}.</li>
 *   <li>{@link #tileFileName} — the on-disk file name for a custom tile.</li>
 * </ol>
 *
 * <h3>Rename format</h3>
 * Same {@code key=value,key=value} shape as the keymap string, but a label
 * is free text and may itself contain {@code ','} or {@code '='}, so both
 * fields are backslash-escaped: {@code '\'}, {@code ','} and {@code '='} are
 * written as {@code "\\"}, {@code "\,"} and {@code "\="}. Keys (package names
 * and the synthetic {@code tvinput://} ids) are escaped too, so the format
 * never depends on what an OEM puts in a TV-input id. The string never
 * contains TAB / CR / LF ({@link #normalizeLabel} folds them to spaces), so
 * it is safe as a {@link SettingsBackup} line value.
 *
 * <p>Entries are written in sorted key order so the same map always produces
 * the same string — a backup file diffs cleanly and tests are deterministic.
 */
final class Customizations {

    private Customizations() { /* no instances */ }

    /** Longest label we keep, in UTF-16 code units. A home-row tile shows
     *  roughly 15-20 characters before ellipsising, so 40 leaves room for a
     *  descriptive name without letting a pasted paragraph into prefs. Also
     *  the EditText's length filter, so the user can never type past it. */
    static final int MAX_LABEL_LEN = 40;

    /** File-name suffix for a stored custom tile (a WEBP image). */
    static final String TILE_EXT = ".webp";

    /** Callback for {@link #parseRenames}; the activity forwards to a map put. */
    interface RenameVisitor { void put(String key, String label); }

    // ── Labels ───────────────────────────────────────────────────────────

    /**
     * Canonical form of a user-entered label: every run of whitespace or
     * control characters (TAB, CR, LF, NUL, …) becomes one space, leading /
     * trailing space is dropped, and the result is capped at
     * {@link #MAX_LABEL_LEN} without splitting a surrogate pair (so an emoji
     * at the cut is dropped whole rather than leaving half a character).
     * Returns {@code ""} for {@code null} / blank input — callers treat that
     * as "restore the app's own name".
     */
    static String normalizeLabel(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LABEL_LEN + 1));
        boolean pendingSpace = false;
        for (int i = 0, n = raw.length(); i < n && sb.length() < MAX_LABEL_LEN; i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\u00A0') {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
                if (sb.length() >= MAX_LABEL_LEN) break;
            }
            sb.append(c);
        }
        int len = sb.length();
        if (len > 0 && Character.isHighSurrogate(sb.charAt(len - 1))) len--;
        while (len > 0 && sb.charAt(len - 1) == ' ') len--;
        sb.setLength(len);
        return sb.toString();
    }

    // ── Rename map ───────────────────────────────────────────────────────

    /**
     * Parse the persisted rename string and forward each valid entry to
     * {@code visitor}. Labels are re-normalised on the way in, so a
     * hand-edited prefs file or backup can never smuggle an over-long or
     * multi-line label onto a tile. Empty tokens (trailing / double commas)
     * are skipped silently. Duplicate keys are all forwarded in file order,
     * so a map-backed visitor keeps the last one — {@link #serializeRenames}
     * never writes duplicates.
     *
     * @return {@code true} if any token was malformed and dropped (no
     *         {@code '='}, empty key, blank label, dangling escape) — the
     *         caller should re-save so prefs converge to a clean shape.
     */
    static boolean parseRenames(String raw, RenameVisitor visitor) {
        if (raw == null || raw.isEmpty()) return false;
        boolean dropped = false;
        StringBuilder key = new StringBuilder(32);
        StringBuilder val = new StringBuilder(32);
        boolean inVal = false;
        int n = raw.length();
        for (int i = 0; i <= n; i++) {
            char c = (i < n) ? raw.charAt(i) : ',';
            if (i < n && c == '\\') {
                if (i + 1 >= n) { dropped = true; break; }   // dangling escape → corrupt tail
                (inVal ? val : key).append(raw.charAt(++i));
                continue;
            }
            if (c == '=' && !inVal) { inVal = true; continue; }
            if (c != ',') { (inVal ? val : key).append(c); continue; }
            // End of token.
            if (inVal || key.length() > 0) {
                String label = normalizeLabel(val.toString());
                if (!inVal || key.length() == 0 || label.isEmpty()) dropped = true;
                else visitor.put(key.toString(), label);
            }
            key.setLength(0);
            val.setLength(0);
            inVal = false;
        }
        return dropped;
    }

    /**
     * Serialise a {@code key → label} map into the persisted string. Entries
     * with an empty key or a label that normalises to empty are skipped.
     * Sorted by key for a deterministic result. Called only when the user
     * renames something (or a reconcile prunes an uninstalled app) — never
     * on a hot path, so the sort's small allocation is irrelevant.
     */
    static String serializeRenames(Map<String, String> renames) {
        if (renames == null || renames.isEmpty()) return "";
        List<String> keys = new ArrayList<>(renames.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder(keys.size() * 40);
        for (int i = 0, n = keys.size(); i < n; i++) {
            String k = keys.get(i);
            if (k == null || k.isEmpty()) continue;
            String label = normalizeLabel(renames.get(k));
            if (label.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            escape(sb, k);
            sb.append('=');
            escape(sb, label);
        }
        return sb.toString();
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ',' || c == '=') sb.append('\\');
            sb.append(c);
        }
    }

    // ── Custom tile files ────────────────────────────────────────────────

    /**
     * File name for the custom tile of {@code key} (a package name, or a
     * synthetic {@code tvinput://…} id). Package-name characters
     * ({@code [A-Za-z0-9._]}) pass through, so a normal app's file is just
     * {@code com.example.app.webp}; every other character — the {@code ':'}
     * and {@code '/'} of a TV-input id, or anything an OEM put in an input id
     * — becomes {@code %XXXX} (its UTF-16 code unit in hex). {@code '%'} can
     * never appear in a package name, so the mapping is injective: two
     * different keys can never share a file. Returns {@code null} for an
     * empty key.
     *
     * <p>No length cap: package names and input ids are far below the 255-byte
     * file-name limit. A pathological id that exceeded it would just make the
     * save fail with the normal "couldn't use that image" toast.
     */
    static String tileFileName(String key) {
        if (key == null || key.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(key.length() + TILE_EXT.length() + 8);
        for (int i = 0, n = key.length(); i < n; i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_') {
                sb.append(c);
            } else {
                sb.append('%');
                for (int shift = 12; shift >= 0; shift -= 4) {
                    sb.append(Character.forDigit((c >> shift) & 0xF, 16));
                }
            }
        }
        return sb.append(TILE_EXT).toString();
    }
}
