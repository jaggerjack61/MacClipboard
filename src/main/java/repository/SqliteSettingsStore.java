package repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite-backed {@link SettingsStore}.
 */
public final class SqliteSettingsStore implements SettingsStore {

    private final Connection connection;

    public SqliteSettingsStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Map<String, String> loadAll() {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT key, value FROM settings";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to load settings", e);
        }
        return result;
    }

    @Override
    public void save(String key, String value) {
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to save setting " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        String sql = "DELETE FROM settings WHERE key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to delete setting " + key, e);
        }
    }
}
