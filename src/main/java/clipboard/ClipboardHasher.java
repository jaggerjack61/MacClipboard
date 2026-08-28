package clipboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import model.ClipboardContentType;

/**
 * Computes stable SHA-256 content hashes used for clipboard duplicate detection.
 */
public final class ClipboardHasher {

    private ClipboardHasher() {
    }

    public static String hash(ClipboardSnapshot snapshot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(snapshot.contentType().name().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (snapshot.contentType() == ClipboardContentType.IMAGE && snapshot.image() != null) {
                md.update(snapshot.image());
            } else if (snapshot.text() != null) {
                md.update(snapshot.text().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
