package clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import model.ClipboardContentType;
import org.junit.jupiter.api.Test;

class ClipboardHasherTest {

    @Test
    void sameContentSameHash() {
        String a = ClipboardHasher.hash(ClipboardSnapshot.text("copy me", null));
        String b = ClipboardHasher.hash(ClipboardSnapshot.text("copy me", null));
        assertEquals(a, b);
    }

    @Test
    void differentContentDifferentHash() {
        String a = ClipboardHasher.hash(ClipboardSnapshot.text("one", null));
        String b = ClipboardHasher.hash(ClipboardSnapshot.text("two", null));
        assertNotEquals(a, b);
    }

    @Test
    void typeAffectsHash() {
        String text = ClipboardHasher.hash(new ClipboardSnapshot(
                ClipboardContentType.TEXT, "x", null, null, null, 1, 1));
        String image = ClipboardHasher.hash(ClipboardSnapshot.image(new byte[]{1}, new byte[]{2}, 1, 1));
        assertNotEquals(text, image);
    }

    @Test
    void imageBytesAffectHash() {
        String a = ClipboardHasher.hash(ClipboardSnapshot.image(new byte[]{1, 2, 3}, null, 1, 1));
        String b = ClipboardHasher.hash(ClipboardSnapshot.image(new byte[]{9, 9, 9}, null, 1, 1));
        assertNotEquals(a, b);
    }
}
