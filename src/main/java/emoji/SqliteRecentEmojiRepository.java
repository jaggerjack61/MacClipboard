package emoji;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import repository.RepositoryException;

/**
 * SQLite-backed {@link RecentEmojiRepository}.
 */
public final class SqliteRecentEmojiRepository implements RecentEmojiRepository {

    private final Connection connection;

    public SqliteRecentEmojiRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public synchronized List<RecentEmojiEntry> entries() {
        String sql = "SELECT character, usage_count, last_used FROM recent_emojis";
        List<RecentEmojiEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new RecentEmojiEntry(rs.getString(1), rs.getInt(2), rs.getLong(3)));
            }
        } catch (SQLException e) {
            throw new RepositoryException("recent emoji read failed", e);
        }
        return result;
    }

    @Override
    public synchronized void recordUse(String character, long timestamp) {
        String sql = """
                INSERT INTO recent_emojis (character, usage_count, last_used)
                VALUES (?, 1, ?)
                ON CONFLICT(character) DO UPDATE SET usage_count = usage_count + 1, last_used = excluded.last_used
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, character);
            ps.setLong(2, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("recent emoji write failed", e);
        }
    }

    @Override
    public synchronized void remove(List<String> characters) {
        if (characters.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM recent_emojis WHERE character IN (");
        for (int i = 0; i < characters.size(); i++) {
            sql.append(i > 0 ? ", ?" : "?");
        }
        sql.append(')');
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < characters.size(); i++) {
                ps.setString(i + 1, characters.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("recent emoji delete failed", e);
        }
    }

    @Override
    public synchronized void clear() {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM recent_emojis");
        } catch (SQLException e) {
            throw new RepositoryException("recent emoji clear failed", e);
        }
    }
}
