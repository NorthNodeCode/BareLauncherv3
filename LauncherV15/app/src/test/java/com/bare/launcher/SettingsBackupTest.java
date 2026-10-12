package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link SettingsBackup}, focused on the round trip and on the
 * v2.3.0 {@code app_renames} key staying forward / backward compatible.
 */
public class SettingsBackupTest {

    @Test public void roundTrip_allKeys() {
        Map<String, String> renames = new HashMap<>();
        renames.put("com.a", "Films, series = fun");
        renames.put("tvinput://com.x/.Hdmi/HW1", "Xbox");
        String renameStr = Customizations.serializeRenames(renames);

        String text = SettingsBackup.serialize("com.a,com.b", 4, "183=com.a", "com.x", 1, renameStr);
        SettingsBackup.Parsed p = SettingsBackup.parse(text);
        assertNotNull(p);
        assertEquals("com.a,com.b", p.str(SettingsBackup.K_APP_ORDER));
        assertEquals(4, p.intVal(SettingsBackup.K_HOME_COUNT, -1));
        assertEquals("183=com.a", p.str(SettingsBackup.K_KEY_MAP));
        assertEquals("com.x", p.str(SettingsBackup.K_HIDDEN));
        assertEquals(1, p.intVal(SettingsBackup.K_CLOCK_MODE, -1));
        assertEquals(renameStr, p.str(SettingsBackup.K_RENAMES));

        Map<String, String> back = new HashMap<>();
        Customizations.parseRenames(p.str(SettingsBackup.K_RENAMES), back::put);
        assertEquals(renames, back);
    }

    @Test public void nullRenames_writtenAsPresentButEmpty() {
        SettingsBackup.Parsed p = SettingsBackup.parse(
                SettingsBackup.serialize("", 1, "", "", 0, null));
        assertNotNull(p);
        assertTrue(p.has(SettingsBackup.K_RENAMES));
        assertEquals("", p.str(SettingsBackup.K_RENAMES));
    }

    @Test public void preRenameBackup_hasNoRenamesKey() {
        // A 2.2.0 backup: restoring it must leave the user's renames alone.
        String v220 = "BLBK\t1\napp_order\tcom.a\nhome_count\t1\nkey_map\t\nhidden_apps\t\nclock_mode\t0\n";
        SettingsBackup.Parsed p = SettingsBackup.parse(v220);
        assertNotNull(p);
        assertFalse(p.has(SettingsBackup.K_RENAMES));
        assertNull(p.str(SettingsBackup.K_RENAMES));
    }

    @Test public void formatVersionUnchanged_soOlderBuildsStillAcceptTheFile() {
        // Older parsers refuse any version above the one they know (1).
        String text = SettingsBackup.serialize("", 1, "", "", 0, "com.a=A");
        assertTrue(text.startsWith("BLBK\t1\n"));
    }

    @Test public void unknownKeysIgnored() {
        SettingsBackup.Parsed p = SettingsBackup.parse("BLBK\t1\nfuture_key\tx\napp_order\tcom.a\n");
        assertNotNull(p);
        assertEquals("com.a", p.str(SettingsBackup.K_APP_ORDER));
    }

    @Test public void newerVersionRejected() {
        assertNull(SettingsBackup.parse("BLBK\t2\napp_order\tcom.a\n"));
    }

    @Test public void badHeaderRejected() {
        assertNull(SettingsBackup.parse(null));
        assertNull(SettingsBackup.parse(""));
        assertNull(SettingsBackup.parse("NOPE\t1\n"));
    }
}
