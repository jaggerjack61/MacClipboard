package repository;

import clipboard.ClipboardHasher;
import clipboard.ClipboardSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the SQLite connection and runs schema migrations.
 *
 * <p>Uses {@code PRAGMA user_version} for a simple, dependency-free migration scheme.</p>
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final Path file;

    public Database(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            migrate();
        } catch (SQLException | IOException e) {
            throw new RepositoryException("Failed to open database at " + file, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public Path file() {
        return file;
    }

    private void migrate() throws SQLException {
        int version = currentVersion();
        try (Statement st = connection.createStatement()) {
            if (version < 1) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS clipboard_items (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            hash TEXT NOT NULL,
                            content_type TEXT NOT NULL,
                            preview TEXT,
                            text_content TEXT,
                            html_content TEXT,
                            image BLOB,
                            thumbnail BLOB,
                            timestamp INTEGER NOT NULL,
                            pinned INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clipboard_hash ON clipboard_items(hash)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clipboard_time ON clipboard_items(timestamp)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clipboard_pinned ON clipboard_items(pinned)");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS settings (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS recent_emojis (
                            character TEXT PRIMARY KEY,
                            usage_count INTEGER NOT NULL DEFAULT 1,
                            last_used INTEGER NOT NULL
                        )
                        """);
                setVersion(1);
            }
            if (version < 2) {
                migrateRichTextHashes();
            }
            if (version < 3) {
                migrateCollections();
            }
        }
    }

    private void migrateRichTextHashes() throws SQLException {
        connection.setAutoCommit(false);
        try {
            // Rehash existing rich text so upgrading does not create duplicate entries.
            try (var read = connection.prepareStatement(
                    "SELECT id, text_content, html_content FROM clipboard_items WHERE content_type = 'RICH_TEXT'");
                 var update = connection.prepareStatement("UPDATE clipboard_items SET hash = ? WHERE id = ?")) {
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        var snapshot = new ClipboardSnapshot(model.ClipboardContentType.RICH_TEXT,
                                rows.getString("text_content"), rows.getString("html_content"),
                                null, null, 0, 0);
                        update.setString(1, ClipboardHasher.hash(snapshot));
                        update.setLong(2, rows.getLong("id"));
                        update.addBatch();
                    }
                }
                update.executeBatch();
            }
            try (var st = connection.createStatement()) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clipboard_display "
                        + "ON clipboard_items(pinned DESC, timestamp DESC, id DESC)");
            }
            setVersion(2);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void migrateCollections() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        created INTEGER NOT NULL
                    )
                    """);
            if (!hasColumn("clipboard_items", "collection_id")) {
                st.executeUpdate("ALTER TABLE clipboard_items ADD COLUMN collection_id INTEGER "
                        + "REFERENCES collections(id) ON DELETE SET NULL");
            }
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clipboard_collection ON clipboard_items(collection_id)");
            setVersion(3);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        }
    }

    private void setVersion(int v) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA user_version = " + v);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to close database", e);
        }
    }
}
