package repository;

import java.util.Map;

/**
 * Simple key/value persistence for application settings.
 */
public interface SettingsStore {

    Map<String, String> loadAll();

    void save(String key, String value);

    void delete(String key);

    default void saveAll(Map<String, String> values) {
        values.forEach(this::save);
    }
}
