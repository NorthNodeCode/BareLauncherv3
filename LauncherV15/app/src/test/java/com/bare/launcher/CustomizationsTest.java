package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link Customizations}: label normalisation, the escaped
 * {@code key=label} rename format, and custom-tile file naming. Pure JVM.
 */
public class CustomizationsTest {

    private static Map<String, String> parse(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        Customizations.parseRenames(raw, out::put);
        return out;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ─── normalizeLabel ──────────────────────────────────────────────────

    @Test public void normalize_nullAndBlank_empty() {
        assertEquals("", Customizations.normalizeLabel(null));
        assertEquals("", Customizations.normalizeLabel(""));
        assertEquals("", Customizations.normalizeLabel("   \t\n "));
    }

    @Test public void normalize_trimsAndCollapsesWhitespace() {
        assertEquals("My TV App", Customizations.normalizeLabel("  My \t TV\r\n\nApp  "));
    }

    @Test public void normalize_foldsControlCharsAndNbsp() {
        assertEquals("a b c", Customizations.normalizeLabel("a\u0000b\u00A0c"));
    }

    @Test public void normalize_keepsSeparatorCharacters() {
        assertEquals("Tom, Jerry = fun\\", Customizations.normalizeLabel("Tom, Jerry = fun\\"));
    }

    @Test public void normalize_capsLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        String out = Customizations.normalizeLabel(sb.toString());
        assertEquals(Customizations.MAX_LABEL_LEN, out.length());
    }

    @Test public void normalize_capNeverSplitsSurrogatePair() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Customizations.MAX_LABEL_LEN - 1; i++) sb.append('a');
        sb.append("\uD83D\uDE00");          // emoji straddles the cap
        String out = Customizations.normalizeLabel(sb.toString());
        assertEquals(Customizations.MAX_LABEL_LEN - 1, out.length());
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - 1)));
    }

    @Test public void normalize_capDropsTrailingSpace() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Customizations.MAX_LABEL_LEN - 1; i++) sb.append('a');
        sb.append(" bcd");                  // the space lands exactly on the cap
        String out = Customizations.normalizeLabel(sb.toString());
        assertEquals(Customizations.MAX_LABEL_LEN - 1, out.length());
        assertFalse(out.endsWith(" "));
    }

    @Test public void normalize_unicodePassesThrough() {
        assertEquals("映画 🎬 مرحبا", Customizations.normalizeLabel("映画 🎬 مرحبا"));
    }

    // ─── parseRenames ────────────────────────────────────────────────────

    @Test public void parse_nullAndEmpty_nothingAndNotDropped() {
        Map<String, String> out = new HashMap<>();
        assertFalse(Customizations.parseRenames(null, out::put));
        assertFalse(Customizations.parseRenames("", out::put));
        assertTrue(out.isEmpty());
    }

    @Test public void parse_single() {
        assertEquals(map("com.a", "Alpha"), parse("com.a=Alpha"));
    }

    @Test public void parse_multiple() {
        assertEquals(map("com.a", "Alpha", "com.b", "Beta"), parse("com.a=Alpha,com.b=Beta"));
    }

    @Test public void parse_blankTokensSkippedSilently() {
        Map<String, String> out = new HashMap<>();
        boolean dropped = Customizations.parseRenames(",,com.a=Alpha,,", out::put);
        assertEquals(map("com.a", "Alpha"), out);
        assertFalse(dropped);
    }

    @Test public void parse_tokenWithoutEquals_dropped() {
        Map<String, String> out = new HashMap<>();
        assertTrue(Customizations.parseRenames("garbage,com.a=Alpha", out::put));
        assertEquals(map("com.a", "Alpha"), out);
    }

    @Test public void parse_emptyKeyOrBlankLabel_dropped() {
        Map<String, String> out = new HashMap<>();
        assertTrue(Customizations.parseRenames("=Alpha,com.b=   ,com.c=Gamma", out::put));
        assertEquals(map("com.c", "Gamma"), out);
    }

    @Test public void parse_danglingEscape_droppedTail() {
        Map<String, String> out = new HashMap<>();
        assertTrue(Customizations.parseRenames("com.a=Alpha,com.b=Be\\", out::put));
        assertEquals(map("com.a", "Alpha"), out);
    }

    @Test public void parse_duplicateKeys_lastWinsInMap() {
        assertEquals(map("com.a", "Two"), parse("com.a=One,com.a=Two"));
    }

    @Test public void parse_unescapedEqualsInValueKept() {
        // Lenient: only the FIRST unescaped '=' separates key from value.
        assertEquals(map("com.a", "x=y"), parse("com.a=x=y"));
    }

    @Test public void parse_renormalisesLabel() {
        assertEquals(map("com.a", "Two words"), parse("com.a=  Two   words "));
    }

    // ─── serializeRenames ────────────────────────────────────────────────

    @Test public void serialize_nullAndEmpty() {
        assertEquals("", Customizations.serializeRenames(null));
        assertEquals("", Customizations.serializeRenames(new HashMap<>()));
    }

    @Test public void serialize_sortedByKey() {
        assertEquals("com.a=Alpha,com.b=Beta",
                Customizations.serializeRenames(map("com.b", "Beta", "com.a", "Alpha")));
    }

    @Test public void serialize_skipsEmptyKeyAndBlankLabel() {
        assertEquals("com.c=Gamma",
                Customizations.serializeRenames(map("", "X", "com.b", "  ", "com.c", "Gamma")));
    }

    @Test public void serialize_escapesSeparators() {
        assertEquals("com.a=Tom\\, Jerry \\= \\\\fun",
                Customizations.serializeRenames(map("com.a", "Tom, Jerry = \\fun")));
    }

    @Test public void serialize_neverContainsTabOrNewline() {
        String s = Customizations.serializeRenames(map("com.a", "a\tb\nc\rd"));
        assertFalse(s.contains("\t"));
        assertFalse(s.contains("\n"));
        assertFalse(s.contains("\r"));
    }

    // ─── round trip ──────────────────────────────────────────────────────

    @Test public void roundTrip_separatorsUnicodeAndTvInputKeys() {
        Map<String, String> in = map(
                "com.a", "Tom, Jerry = fun",
                "com.b", "back\\slash",
                "com.c", "映画 🎬 مرحبا",
                "tvinput://com.mediatek.tvinput/.hdmi.HDMIInputService/HW5", "Xbox",
                "tvinput://odd=id,with/commas", "Weird input");
        assertEquals(in, parse(Customizations.serializeRenames(in)));
    }

    @Test public void roundTrip_isStable() {
        Map<String, String> in = map("com.a", "A, B", "com.b", "C=D");
        String once = Customizations.serializeRenames(in);
        assertEquals(once, Customizations.serializeRenames(parse(once)));
    }

    // ─── tileFileName ────────────────────────────────────────────────────

    @Test public void tileFileName_emptyIsNull() {
        assertNull(Customizations.tileFileName(null));
        assertNull(Customizations.tileFileName(""));
    }

    @Test public void tileFileName_packagePassesThrough() {
        assertEquals("com.example.app_2.webp", Customizations.tileFileName("com.example.app_2"));
    }

    @Test public void tileFileName_tvInputIsPathSafe() {
        String f = Customizations.tileFileName("tvinput://com.x/.Hdmi/HW1");
        assertFalse(f.contains("/"));
        assertFalse(f.contains(":"));
        assertEquals("tvinput%003a%002f%002fcom.x%002f.Hdmi%002fHW1.webp", f);
    }

    @Test public void tileFileName_isInjective() {
        // '%' is escaped too, so a key that already looks encoded can't
        // collide with the key it appears to encode.
        assertNotEquals(Customizations.tileFileName("a/b"), Customizations.tileFileName("a%002fb"));
        assertNotEquals(Customizations.tileFileName("a_b"), Customizations.tileFileName("a/b"));
    }

    @Test public void tileFileName_unicodeEncoded() {
        assertEquals("tv%6620.webp", Customizations.tileFileName("tv\u6620"));
    }
}
