package repository;

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
            // Future migrations: if (version < 2) { ...; setVersion(2); }
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
