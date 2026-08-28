package hotkey;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed representation of a global shortcut such as {@code "MAC+SHIFT+V"} or
 * {@code "CTRL+ALT+C"}. Stored in settings and interpreted by the platform-specific
 * {@link GlobalHotkeyService}.
 *
 * <p>{@link #keyCode()} is a JNativeHook key code (USB HID usage, e.g. {@code VC_V = 47}),
 * <em>not</em> the ASCII value of the character. See {@link #toNativeKeyCode(char)}.</p>
 */
public record ShortcutModifier(Set<Modifier> modifiers, int keyCode, char keyChar) {

    public enum Modifier {MAC, CTRL, ALT, SHIFT}

    /**
     * Maps a letter/digit character to the JNativeHook key code reported by
     * {@code NativeKeyEvent#getKeyCode()} on macOS/Windows/Linux. These are HID usage
     * values and do not follow ASCII ordering (e.g. 'A' -> 30, 'V' -> 47, '1' -> 2).
     *
     * @throws IllegalArgumentException when the character is not a supported key
     */
    public static int toNativeKeyCode(char c) {
        char u = Character.toUpperCase(c);
        return switch (u) {
            case 'Q' -> 16;
            case 'W' -> 17;
            case 'E' -> 18;
            case 'R' -> 19;
            case 'T' -> 20;
            case 'Y' -> 21;
            case 'U' -> 22;
            case 'I' -> 23;
            case 'O' -> 24;
            case 'P' -> 25;
            case 'A' -> 30;
            case 'S' -> 31;
            case 'D' -> 32;
            case 'F' -> 33;
            case 'G' -> 34;
            case 'H' -> 35;
            case 'J' -> 36;
            case 'K' -> 37;
            case 'L' -> 38;
            case 'Z' -> 44;
            case 'X' -> 45;
            case 'C' -> 46;
            case 'V' -> 47;
            case 'B' -> 48;
            case 'N' -> 49;
            case 'M' -> 50;
            case '1' -> 2;
            case '2' -> 3;
            case '3' -> 4;
            case '4' -> 5;
            case '5' -> 6;
            case '6' -> 7;
            case '7' -> 8;
            case '8' -> 9;
            case '9' -> 10;
            case '0' -> 11;
            default -> throw new IllegalArgumentException("unsupported shortcut key: " + c);
        };
    }

    /**
     * Parses a human/setting-level shortcut string. Returns empty when the string
     * cannot be interpreted. The final segment must be a single letter/digit key.
     */
    public static ShortcutModifier parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().toUpperCase(Locale.ROOT).split("\\+");
        if (parts.length < 2) {
            return null;
        }
        EnumSet<Modifier> mods = EnumSet.noneOf(Modifier.class);
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim()) {
                case "MAC", "CMD", "COMMAND", "META" -> mods.add(Modifier.MAC);
                case "CTRL", "CONTROL" -> mods.add(Modifier.CTRL);
                case "ALT", "OPTION" -> mods.add(Modifier.ALT);
                case "SHIFT" -> mods.add(Modifier.SHIFT);
                default -> {
                    // unknown modifier -> ignore
                }
            }
        }
        String key = parts[parts.length - 1].trim();
        if (key.length() != 1) {
            return null;
        }
        char c = key.charAt(0);
        if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
            return null;
        }
        return new ShortcutModifier(mods, toNativeKeyCode(c), c);
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Modifier m : modifiers) {
            sb.append(switch (m) {
                case MAC -> "⌘";
                case CTRL -> "⌃";
                case ALT -> "⌥";
                case SHIFT -> "⇧";
            });
        }
        sb.append(keyChar);
        return sb.toString();
    }
}
