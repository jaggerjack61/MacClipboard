package hotkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShortcutModifierTest {

    @Test
    void parsesDefault() {
        ShortcutModifier s = ShortcutModifier.parse("MAC+SHIFT+V");
        assertNotNull(s);
        assertTrue(s.has(ShortcutModifier.Modifier.MAC));
        assertTrue(s.has(ShortcutModifier.Modifier.SHIFT));
        assertFalse(s.has(ShortcutModifier.Modifier.CTRL));
        assertEquals('V', s.keyChar());
        // JNativeHook/HID key code for 'V' (VC_V), not the ASCII value 86.
        assertEquals(47, s.keyCode());
    }

    @Test
    void mapsLettersAndDigitsToNativeKeyCodes() {
        assertEquals(30, ShortcutModifier.toNativeKeyCode('A'));
        assertEquals(47, ShortcutModifier.toNativeKeyCode('V'));
        assertEquals(47, ShortcutModifier.toNativeKeyCode('v'));
        assertEquals(2, ShortcutModifier.toNativeKeyCode('1'));
        assertEquals(11, ShortcutModifier.toNativeKeyCode('0'));
        for (char c = 'A'; c <= 'Z'; c++) {
            assertTrue(ShortcutModifier.toNativeKeyCode(c) > 0, "no key code for " + c);
        }
    }

    @Test
    void parsesControlAlt() {
        ShortcutModifier s = ShortcutModifier.parse("CTRL+ALT+C");
        assertTrue(s.has(ShortcutModifier.Modifier.CTRL));
        assertTrue(s.has(ShortcutModifier.Modifier.ALT));
        assertFalse(s.has(ShortcutModifier.Modifier.MAC));
    }

    @Test
    void rejectsInvalid() {
        assertNull(ShortcutModifier.parse(null));
        assertNull(ShortcutModifier.parse(""));
        assertNull(ShortcutModifier.parse("F12"));
        assertNull(ShortcutModifier.parse("SHIFT+ENTER"));
    }

    @Test
    void formatsWithSymbols() {
        assertEquals("\u2318\u21e7V", ShortcutModifier.parse("MAC+SHIFT+V").format());
    }
}
