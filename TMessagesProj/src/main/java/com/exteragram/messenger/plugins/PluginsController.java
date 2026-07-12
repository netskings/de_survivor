package com.exteragram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginDescriptor;
import org.telegram.messenger.plugins.PluginManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source-compatible facade for plugins which access exteraGram's Java layer.
 * The authoritative state remains in PluginManager/Python runtime.
 */
public final class PluginsController {
    private static volatile PluginsController instance;

    public final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    public final File pluginsDir;
    public final SharedPreferences preferences;

    private final PluginManager manager = PluginManager.getInstance();

    private PluginsController() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            throw new IllegalStateException("Application context is not initialized");
        }
        pluginsDir = new File(context.getFilesDir(), "plugins");
        // Kept for plugins which reflect this historical field. New settings
        // are persisted by the shared Python core.
        preferences = new LegacyPluginPreferences(
                context.getSharedPreferences("python_plugins_legacy", Context.MODE_PRIVATE)
        );
        refreshPlugins();
    }

    public static PluginsController getInstance() {
        PluginsController local = instance;
        if (local == null) {
            synchronized (PluginsController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PluginsController();
                }
            }
        }
        local.refreshPlugins();
        return local;
    }

    public void refreshPlugins() {
        plugins.clear();
        for (PluginDescriptor descriptor : manager.listPlugins()) {
            plugins.put(descriptor.id, new Plugin(descriptor));
        }
    }

    public String getPluginPath(String pluginId) {
        Plugin plugin = plugins.get(pluginId);
        return plugin == null ? new File(pluginsDir, pluginId + ".py").getAbsolutePath() : plugin.path;
    }

    public boolean isPlugin(String path) {
        PyObject result = manager.callBridge("validate_plugin_from_file", path);
        return result != null;
    }

    public void loadPluginFromFile(String path) {
        loadPluginFromFile(path, null, null);
    }

    public void loadPluginFromFile(String path, Object callback) {
        loadPluginFromFile(path, null, callback);
    }

    public void loadPluginFromFile(String path, Object unused, Object callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String error = manager.installPlugin(new File(path)) ? null : "Plugin installation failed";
            refreshPlugins();
            invokeCallback(callback, error);
        });
    }

    public void setPluginEnabled(String pluginId, boolean enabled) {
        setPluginEnabled(pluginId, enabled, null);
    }

    public void setPluginEnabled(String pluginId, boolean enabled, Object callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String error = manager.setPluginEnabled(pluginId, enabled) ? null : "Unable to change plugin state";
            refreshPlugins();
            invokeCallback(callback, error);
        });
    }

    public void deletePlugin(String pluginId) {
        deletePlugin(pluginId, null);
    }

    public void deletePlugin(String pluginId, Object callback) {
        Utilities.globalQueue.postRunnable(() -> {
            PyObject result = manager.callBridge("delete_plugin", pluginId);
            String error = decodeBoolean(result, false)
                    ? null : "Unable to delete plugin";
            refreshPlugins();
            invokeCallback(callback, error);
        });
    }

    public PluginValidationResult validatePluginFromFile(String path) {
        PyObject result = manager.callBridge("validate_plugin_from_file", path);
        if (result == null) {
            return new PluginValidationResult(false, null, "Validation failed");
        }
        try {
            String id = result.get("id") == null ? null : result.get("id").toString();
            return new PluginValidationResult(true, id, null);
        } catch (Throwable error) {
            return new PluginValidationResult(false, null, error.toString());
        }
    }

    public String addMenuItem(String pluginId, PyObject menuItem) {
        PyObject result = manager.callBridge("add_menu_item", pluginId, menuItem);
        if (result == null) return null;
        try {
            return result.toJava(Object.class) == null ? null : result.toString();
        } catch (Throwable error) {
            FileLog.e(error);
            return null;
        }
    }

    public boolean removeMenuItem(String pluginId, String itemId) {
        PyObject result = manager.callBridge("remove_menu_item", pluginId, itemId);
        return decodeBoolean(result, false);
    }

    public void removeMenuItemsByPluginId(String pluginId) {
        manager.callBridge("remove_menu_items_by_plugin_id", pluginId);
    }

    private static void invokeCallback(Object callback, Object value) {
        if (callback == null) {
            return;
        }
        try {
            if (callback instanceof PyObject) {
                PluginManager.getInstance().callBridge(
                        "invoke_external_callback",
                        "PluginsController.callback",
                        callback,
                        value
                );
                return;
            }
            for (Method method : callback.getClass().getMethods()) {
                if ((method.getName().equals("run") || method.getName().equals("onResult"))
                        && method.getParameterTypes().length == 1) {
                    method.invoke(callback, value);
                    return;
                }
            }
        } catch (Throwable error) {
            FileLog.e(error);
        }
    }

    private static boolean decodeBoolean(PyObject value, boolean fallback) {
        if (value == null) return fallback;
        try {
            return value.toBoolean();
        } catch (Throwable error) {
            FileLog.e(error);
            return fallback;
        }
    }

    public static final class PluginValidationResult {
        public final boolean valid;
        public final String pluginId;
        public final String error;

        public PluginValidationResult(boolean valid, String pluginId, String error) {
            this.valid = valid;
            this.pluginId = pluginId;
            this.error = error;
        }
    }
}
