package com.exteragram.messenger.plugins;

import android.content.SharedPreferences;

import com.chaquo.python.PyObject;

import org.telegram.messenger.plugins.PluginManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences-compatible read view over legacy and JSON plugin settings. */
final class LegacyPluginPreferences implements SharedPreferences {
    private final SharedPreferences delegate;

    LegacyPluginPreferences(SharedPreferences delegate) {
        this.delegate = delegate;
    }

    @Override
    public Map<String, ?> getAll() {
        HashMap<String, Object> values = new HashMap<>();
        values.putAll(delegate.getAll());
        PyObject flattened = PluginManager.getInstance().callBridge("legacy_preferences_all");
        if (flattened != null) {
            try {
                Map<?, ?> pythonValues = flattened.toJava(Map.class);
                for (Map.Entry<?, ?> entry : pythonValues.entrySet()) {
                    values.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        return values;
    }

    private Object value(String key) {
        return getAll().get(key);
    }

    @Override public String getString(String key, String defValue) {
        Object value = value(key); return value instanceof String ? (String) value : defValue;
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = value(key); return value instanceof Set ? (Set<String>) value : defValues;
    }
    @Override public int getInt(String key, int defValue) {
        Object value = value(key); return value instanceof Number ? ((Number) value).intValue() : defValue;
    }
    @Override public long getLong(String key, long defValue) {
        Object value = value(key); return value instanceof Number ? ((Number) value).longValue() : defValue;
    }
    @Override public float getFloat(String key, float defValue) {
        Object value = value(key); return value instanceof Number ? ((Number) value).floatValue() : defValue;
    }
    @Override public boolean getBoolean(String key, boolean defValue) {
        Object value = value(key); return value instanceof Boolean ? (Boolean) value : defValue;
    }
    @Override public boolean contains(String key) { return getAll().containsKey(key); }
    @Override public Editor edit() { return delegate.edit(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.registerOnSharedPreferenceChangeListener(listener);
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
