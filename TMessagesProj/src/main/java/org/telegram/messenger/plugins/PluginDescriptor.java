package org.telegram.messenger.plugins;

import com.chaquo.python.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable UI snapshot. No live plugin object crosses into Android views. */
public final class PluginDescriptor {
    public final String id;
    public final String name;
    public final String description;
    public final String author;
    public final String version;
    public final String icon;
    public final String appVersion;
    public final String minVersion;
    public final String sdkVersion;
    public final List<String> requirements;
    public final String path;
    public final String errorMessage;
    public final boolean enabled;
    public final boolean initialized;
    public final int crashCount;

    private PluginDescriptor(
            String id,
            String name,
            String description,
            String author,
            String version,
            String icon,
            String appVersion,
            String minVersion,
            String sdkVersion,
            List<String> requirements,
            String path,
            String errorMessage,
            boolean enabled,
            boolean initialized,
            int crashCount
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.version = version;
        this.icon = icon;
        this.appVersion = appVersion;
        this.minVersion = minVersion;
        this.sdkVersion = sdkVersion;
        this.requirements = requirements;
        this.path = path;
        this.errorMessage = errorMessage;
        this.enabled = enabled;
        this.initialized = initialized;
        this.crashCount = crashCount;
    }

    public static PluginDescriptor fromPython(PyObject value) {
        ArrayList<String> requirements = new ArrayList<>();
        PyObject requirementsValue = value.get("requirements");
        if (requirementsValue != null) {
            try {
                for (PyObject requirement : requirementsValue.asList()) {
                    requirements.add(requirement.toString());
                }
            } catch (Throwable ignored) {
                // Invalid metadata is reported by the Python loader; keep the row usable.
            }
        }
        return new PluginDescriptor(
                string(value, "id"),
                string(value, "name"),
                string(value, "description"),
                string(value, "author"),
                string(value, "version"),
                string(value, "icon"),
                string(value, "app_version"),
                string(value, "min_version"),
                string(value, "sdk_version"),
                Collections.unmodifiableList(requirements),
                string(value, "path"),
                string(value, "error_message"),
                bool(value, "enabled"),
                bool(value, "initialized"),
                integer(value, "crash_count")
        );
    }

    private static String string(PyObject object, String key) {
        PyObject value = object.get(key);
        if (value == null) {
            return "";
        }
        Object converted = value.toJava(Object.class);
        return converted == null ? "" : value.toString();
    }

    private static boolean bool(PyObject object, String key) {
        PyObject value = object.get(key);
        if (value == null || value.toJava(Object.class) == null) {
            return false;
        }
        return value.toBoolean();
    }

    private static int integer(PyObject object, String key) {
        PyObject value = object.get(key);
        if (value == null || value.toJava(Object.class) == null) {
            return 0;
        }
        return value.toInt();
    }
}
