package repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import model.ClipboardContentType;
import model.ClipboardItem;

/**
 * SQLite-backed {@link ClipboardRepository}. All clipboard data stays in the local
 * database file; nothing is ever sent over the network.
 */
public final class SqliteClipboardRepository implements ClipboardRepository {

    private final Connection connection;

    public SqliteClipboardRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public synchronized ClipboardItem insert(ClipboardItem item) {
        String sql = """
                INSERT INTO clipboard_items
                    (hash, content_type, preview, text_content, html_content, image, thumbnail, timestamp, pinned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, item.hash());
            ps.setString(2, item.contentType().name());
            ps.setString(3, item.preview());
            ps.setString(4, item.textContent());
            ps.setString(5, item.htmlContent());
            setBytesOrNull(ps, 6, item.image());
            setBytesOrNull(ps, 7, item.thumbnail());
            ps.setLong(8, item.timestamp());
            ps.setInt(9, item.pinned() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return item.withId(keys.getLong(1));
                }
            }
            return item;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to insert clipboard item", e);
        }
    }

    @Override
    public synchronized Optional<ClipboardItem> findByHash(String hash) {
        String sql = "SELECT * FROM clipboard_items WHERE hash = ? ORDER BY timestamp DESC, id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to find by hash", e);
        }
    }

    @Override
    public synchronized Optional<ClipboardItem> findById(long id) {
        String sql = "SELECT * FROM clipboard_items WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to find by id", e);
        }
    }

    @Override
    public synchronized List<ClipboardItem> findRecent(String query, int limit) {
        boolean filtered = query != null && !query.isBlank();
        String sql = filtered
                ? "SELECT * FROM clipboard_items WHERE text_content LIKE ? ESCAPE '\\' ORDER BY pinned DESC, timestamp DESC, id DESC LIMIT ?"
                : "SELECT * FROM clipboard_items ORDER BY pinned DESC, timestamp DESC, id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            if (filtered) {
                ps.setString(idx++, "%" + escapeLike(query.trim()) + "%");
            }
            ps.setInt(idx, Math.max(1, limit));
            List<ClipboardItem> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to query recent items", e);
        }
    }

    @Override
    public synchronized Optional<String> latestHash() {
        String sql = "SELECT hash FROM clipboard_items ORDER BY timestamp DESC, id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to read latest hash", e);
        }
    }

    @Override
    public synchronized boolean setPinned(long id, boolean pinned) {
        String sql = "UPDATE clipboard_items SET pinned = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pinned ? 1 : 0);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to update pin state", e);
        }
    }

    @Override
    public synchronized boolean touch(long id, long newTimestamp) {
        String sql = "UPDATE clipboard_items SET timestamp = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, newTimestamp);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to touch item", e);
        }
    }

    @Override
    public synchronized boolean delete(long id) {
        String sql = "DELETE FROM clipboard_items WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to delete item", e);
        }
    }

    @Override
    public synchronized int deleteUnpinned() {
        String sql = "DELETE FROM clipboard_items WHERE pinned = 0";
        try (Statement st = connection.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to clear unpinned items", e);
        }
    }

    @Override
    public synchronized int deleteAll() {
        String sql = "DELETE FROM clipboard_items";
        try (Statement st = connection.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to clear all items", e);
        }
    }

    @Override
    public synchronized int enforceLimit(int maxUnpinned) {
        String sql = """
                DELETE FROM clipboard_items
                WHERE pinned = 0 AND id NOT IN (
                    SELECT id FROM clipboard_items WHERE pinned = 0
                    ORDER BY timestamp DESC, id DESC LIMIT ?
                )
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, maxUnpinned));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to enforce history limit", e);
        }
    }

    @Override
    public synchronized int deleteOlderThan(long olderThanMillis) {
        String sql = "DELETE FROM clipboard_items WHERE pinned = 0 AND timestamp < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, olderThanMillis);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to prune old items", e);
        }
    }

    @Override
    public synchronized long count() {
        String sql = "SELECT COUNT(*) FROM clipboard_items";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to count items", e);
        }
    }

    private static void setBytesOrNull(PreparedStatement ps, int index, byte[] bytes) throws SQLException {
        if (bytes == null) {
            ps.setNull(index, Types.BLOB);
        } else {
            ps.setBytes(index, bytes);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ClipboardItem map(ResultSet rs) throws SQLException {
        return ClipboardItem.builder()
                .id(rs.getLong("id"))
                .contentType(parseType(rs.getString("content_type")))
                .hash(rs.getString("hash"))
                .preview(rs.getString("preview"))
                .textContent(rs.getString("text_content"))
                .htmlContent(rs.getString("html_content"))
                .image(rs.getBytes("image"))
                .thumbnail(rs.getBytes("thumbnail"))
                .timestamp(rs.getLong("timestamp"))
                .pinned(rs.getInt("pinned") != 0)
                .build();
    }

    private static ClipboardContentType parseType(String name) {
        try {
            return ClipboardContentType.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ClipboardContentType.UNKNOWN;
        }
    }
}
