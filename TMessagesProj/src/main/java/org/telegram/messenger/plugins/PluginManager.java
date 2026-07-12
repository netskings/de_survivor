package org.telegram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The narrow Java/Chaquopy boundary used by Telegram hot paths.
 *
 * All plugin ordering, registration and exception isolation lives in Python so
 * the legacy SDK and API v2 have exactly the same semantics. This class never
 * lets a Python exception escape into Telegram.
 */
public final class PluginManager {

    public static final String SDK_VERSION = "1.4.3.10";

    private static final String PREFS = "python_plugins";
    private static final String KEY_SAFE_MODE = "safe_mode";
    private static final String KEY_SAFE_MODE_ONCE = "safe_mode_once";
    private static final String KEY_STARTUP_PENDING = "startup_pending";
    private static final String KEY_SAFE_MODE_REASON = "safe_mode_reason";

    private static volatile PluginManager instance;

    public static PluginManager getInstance() {
        PluginManager local = instance;
        if (local == null) {
            synchronized (PluginManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PluginManager();
                }
            }
        }
        return local;
    }

    public enum HookStrategy {
        DEFAULT,
        CANCEL,
        MODIFY,
        MODIFY_FINAL
    }

    public static final class HookOutcome<T> {
        public final HookStrategy strategy;
        public final T value;

        private HookOutcome(HookStrategy strategy, T value) {
            this.strategy = strategy;
            this.value = value;
        }

        public boolean isCancelled() {
            return strategy == HookStrategy.CANCEL;
        }

        public static <T> HookOutcome<T> defaultValue(T value) {
            return new HookOutcome<>(HookStrategy.DEFAULT, value);
        }
    }

    public static final class PostRequestOutcome {
        public final HookStrategy strategy;
        public final TLObject response;
        public final TLRPC.TL_error error;

        private PostRequestOutcome(HookStrategy strategy, TLObject response, TLRPC.TL_error error) {
            this.strategy = strategy;
            this.response = response;
            this.error = error;
        }

        public boolean isCancelled() {
            return strategy == HookStrategy.CANCEL;
        }
    }

    private final Object runtimeLock = new Object();
    private Context context;
    private SharedPreferences preferences;
    private volatile PyObject bridge;
    private volatile boolean bootstrapped;
    private volatile boolean loaded;
    private volatile boolean safeModeForThisStart;
    private final CopyOnWriteArrayList<SettingsReloadListener> settingsReloadListeners =
            new CopyOnWriteArrayList<>();

    public interface SettingsReloadListener {
        void onPluginSettingsReload(String pluginId);
    }

    private PluginManager() {
    }

    /** Starts only the trusted bundled runtime. Third-party code is loaded later. */
    public void bootstrap(Context appContext) {
        synchronized (runtimeLock) {
            if (bootstrapped) {
                return;
            }
            context = appContext.getApplicationContext();
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            boolean previousStartupFailed = preferences.getBoolean(KEY_STARTUP_PENDING, false);
            safeModeForThisStart = preferences.getBoolean(KEY_SAFE_MODE, false)
                    || preferences.getBoolean(KEY_SAFE_MODE_ONCE, false)
                    || previousStartupFailed;
            SharedPreferences.Editor editor = preferences.edit()
                    .remove(KEY_SAFE_MODE_ONCE)
                    .remove(KEY_STARTUP_PENDING);
            if (previousStartupFailed) {
                editor.putBoolean(KEY_SAFE_MODE, true)
                        .putString(KEY_SAFE_MODE_REASON, "The previous plugin startup did not complete");
            }
            editor.apply();

            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(context));
                }
                File pluginsDir = new File(context.getFilesDir(), "plugins");
                File stateDir = new File(context.getFilesDir(), "python_plugins");
                ensureDirectory(pluginsDir);
                ensureDirectory(stateDir);
                bridge = Python.getInstance().getModule("plugin_runtime.android_bridge");
                bridge.callAttr(
                        "initialize",
                        pluginsDir.getAbsolutePath(),
                        stateDir.getAbsolutePath(),
                        BuildVars.BUILD_VERSION_STRING,
                        SDK_VERSION
                );
                bridge.callAttr("set_safe_mode", safeModeForThisStart);
                bootstrapped = true;
                if (safeModeForThisStart) {
                    FileLog.d("Python plugins: safe mode, trusted manager started without third-party code");
                }
            } catch (Throwable error) {
                bootstrapped = true;
                safeModeForThisStart = true;
                preferences.edit()
                        .putBoolean(KEY_SAFE_MODE, true)
                        .putString(KEY_SAFE_MODE_REASON, "Bundled Python runtime failed to initialize")
                        .apply();
                recordBridgeError("bootstrap", error);
            }
        }
    }

    /** Loads enabled plugins after Telegram account/controller initialization. */
    public void loadEnabledPlugins() {
        synchronized (runtimeLock) {
            if (loaded || bridge == null || safeModeForThisStart) {
                return;
            }
            preferences.edit().putBoolean(KEY_STARTUP_PENDING, true).commit();
            try {
                // Hooks registered from on_plugin_load and operations emitted by
                // START must see the same dispatcher as normal runtime traffic.
                loaded = true;
                bridge.callAttr("load_enabled_plugins");
                bridge.callAttr("invoke_app_event", "START");
                preferences.edit().remove(KEY_STARTUP_PENDING).apply();
            } catch (Throwable error) {
                loaded = false;
                safeModeForThisStart = true;
                preferences.edit()
                        .putBoolean(KEY_SAFE_MODE, true)
                        .putString(KEY_SAFE_MODE_REASON, "Plugin loading failed before isolation was available")
                        .apply();
                recordBridgeError("load_enabled_plugins", error);
            }
        }
    }

    public boolean isRuntimeAvailable() {
        // In safe mode the trusted core remains available for AST discovery and
        // disabling/deleting a broken plugin; only third-party loading is gated.
        return bridge != null;
    }

    public boolean isSafeMode() {
        return safeModeForThisStart;
    }

    public boolean isSafeModeConfigured() {
        return preferences != null && preferences.getBoolean(KEY_SAFE_MODE, false);
    }

    public String getSafeModeReason() {
        return preferences == null ? "" : preferences.getString(KEY_SAFE_MODE_REASON, "");
    }

    /** Takes effect on the next process start. */
    public void setSafeMode(boolean enabled) {
        if (preferences == null) {
            return;
        }
        preferences.edit()
                .putBoolean(KEY_SAFE_MODE, enabled)
                .putString(KEY_SAFE_MODE_REASON, enabled ? "Enabled by user" : "")
                .remove(KEY_STARTUP_PENDING)
                .apply();
    }

    public void requestSafeModeOnce() {
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_SAFE_MODE_ONCE, true).apply();
        }
    }

    public void dispatchAppEvent(String eventName) {
        callVoid("invoke_app_event", eventName);
    }

    public HookOutcome<SendMessagesHelper.SendMessageParams> dispatchSendMessage(
            int account,
            SendMessagesHelper.SendMessageParams params
    ) {
        return callSingleOutcome(
                "dispatch_send",
                params,
                SendMessagesHelper.SendMessageParams.class,
                account,
                params
        );
    }

    public HookOutcome<TLObject> dispatchPreRequest(int account, String requestName, TLObject request) {
        return callSingleOutcome(
                "dispatch_pre_request",
                request,
                TLObject.class,
                requestName,
                account,
                request
        );
    }

    public PostRequestOutcome dispatchPostRequest(
            int account,
            String requestName,
            TLObject response,
            TLRPC.TL_error error
    ) {
        if (!isRuntimeAvailable() || !loaded) {
            return new PostRequestOutcome(HookStrategy.DEFAULT, response, error);
        }
        try {
            List<PyObject> values = bridge.callAttr(
                    "dispatch_post_request", requestName, account, response, error
            ).asList();
            if (values.size() < 3) {
                throw new IllegalStateException("post request hook returned fewer than three values");
            }
            HookStrategy strategy = parseStrategy(values.get(0));
            TLObject modifiedResponse = values.get(1).toJava(TLObject.class);
            TLRPC.TL_error modifiedError = values.get(2).toJava(TLRPC.TL_error.class);
            return new PostRequestOutcome(strategy, modifiedResponse, modifiedError);
        } catch (Throwable hookError) {
            recordBridgeError("dispatch_post_request:" + requestName, hookError);
            return new PostRequestOutcome(HookStrategy.DEFAULT, response, error);
        }
    }

    public HookOutcome<TLRPC.Updates> dispatchUpdates(int account, TLRPC.Updates updates) {
        return callSingleOutcome(
                "dispatch_updates",
                updates,
                TLRPC.Updates.class,
                updates.getClass().getSimpleName(),
                account,
                updates
        );
    }

    public HookOutcome<TLRPC.Update> dispatchUpdate(int account, TLRPC.Update update) {
        return callSingleOutcome(
                "dispatch_update",
                update,
                TLRPC.Update.class,
                update.getClass().getSimpleName(),
                account,
                update
        );
    }

    public PyObject callBridge(String name, Object... args) {
        if (!isRuntimeAvailable()) {
            return null;
        }
        try {
            return bridge.callAttr(name, args);
        } catch (Throwable error) {
            recordBridgeError(name, error);
            return null;
        }
    }

    public List<PluginDescriptor> listPlugins() {
        PyObject result = callBridge("list_plugins");
        if (result == null) {
            return Collections.emptyList();
        }
        ArrayList<PluginDescriptor> plugins = new ArrayList<>();
        try {
            for (PyObject value : result.asList()) {
                plugins.add(PluginDescriptor.fromPython(value));
            }
        } catch (Throwable error) {
            recordBridgeError("list_plugins:decode", error);
        }
        return plugins;
    }

    public boolean setPluginEnabled(String pluginId, boolean enabled) {
        return bridgeSucceeded("set_enabled:decode", callBridge("set_enabled", pluginId, enabled));
    }

    public boolean installPlugin(File source) {
        return bridgeSucceeded("install:decode", callBridge("install", source.getAbsolutePath()));
    }

    public List<PyObject> getSerializedSettings(String pluginId) {
        PyObject result = callBridge("serialize_settings", pluginId);
        if (result == null) {
            return Collections.emptyList();
        }
        try {
            return result.asList();
        } catch (Throwable error) {
            recordBridgeError("serialize_settings:decode:" + pluginId, error);
            return Collections.emptyList();
        }
    }

    public List<PyObject> getSerializedSubSettings(String pluginId, Object index) {
        PyObject result = callBridge("serialize_sub_settings", pluginId, index);
        if (result == null) {
            return Collections.emptyList();
        }
        try {
            return result.asList();
        } catch (Throwable error) {
            recordBridgeError("serialize_sub_settings:decode:" + pluginId, error);
            return Collections.emptyList();
        }
    }

    public boolean changeSetting(String pluginId, Object index, Object value) {
        PyObject result = callBridge("setting_changed", pluginId, index, value);
        return decodeBoolean("setting_changed:decode:" + pluginId, result, false);
    }

    public boolean clickSetting(String pluginId, Object index, ViewForPlugin view, boolean longClick) {
        return clickSetting(pluginId, index, view, null, longClick);
    }

    public boolean clickSetting(
            String pluginId,
            Object index,
            ViewForPlugin view,
            Object item,
            boolean longClick
    ) {
        PyObject result = callBridge(
                "setting_clicked",
                pluginId,
                index,
                longClick ? "long_click" : "click",
                view == null ? null : view.view,
                item
        );
        return decodeBoolean("setting_clicked:decode:" + pluginId, result, false);
    }

    public void addSettingsReloadListener(SettingsReloadListener listener) {
        if (listener != null) {
            settingsReloadListeners.addIfAbsent(listener);
        }
    }

    public void removeSettingsReloadListener(SettingsReloadListener listener) {
        settingsReloadListeners.remove(listener);
    }

    public void notifySettingsReload(String pluginId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (SettingsReloadListener listener : settingsReloadListeners) {
                try {
                    listener.onPluginSettingsReload(pluginId);
                } catch (Throwable error) {
                    recordBridgeError("reload_settings:" + pluginId, error);
                }
            }
        });
    }

    public android.view.View createCustomSettingView(
            String pluginId,
            Object index,
            android.content.Context viewContext,
            Object listView,
            int currentAccount,
            int classGuid,
            Object resourcesProvider
    ) {
        PyObject result = callBridge(
                "create_custom_view",
                pluginId,
                index,
                viewContext,
                listView,
                currentAccount,
                classGuid,
                resourcesProvider
        );
        if (result == null) {
            return null;
        }
        try {
            return result.toJava(android.view.View.class);
        } catch (Throwable error) {
            recordBridgeError("create_custom_view:" + pluginId, error);
            return null;
        }
    }

    public org.telegram.ui.Components.UItem createCustomSettingItem(
            String pluginId,
            Object index
    ) {
        PyObject result = callBridge("create_custom_item", pluginId, index);
        if (result == null) {
            return null;
        }
        try {
            return result.toJava(org.telegram.ui.Components.UItem.class);
        } catch (Throwable error) {
            recordBridgeError("create_custom_item:" + pluginId, error);
            return null;
        }
    }

    /** Keeps android.view.View out of the hot-path API surface. */
    public static final class ViewForPlugin {
        public final android.view.View view;

        public ViewForPlugin(android.view.View view) {
            this.view = view;
        }
    }

    public void shutdown() {
        synchronized (runtimeLock) {
            if (bridge == null) {
                return;
            }
            try {
                if (loaded) {
                    bridge.callAttr("invoke_app_event", "STOP");
                }
                bridge.callAttr("shutdown");
            } catch (Throwable error) {
                recordBridgeError("shutdown", error);
            } finally {
                loaded = false;
            }
        }
    }

    private <T> HookOutcome<T> callSingleOutcome(
            String method,
            T fallback,
            Class<T> expectedClass,
            Object... args
    ) {
        if (!isRuntimeAvailable() || !loaded) {
            return HookOutcome.defaultValue(fallback);
        }
        try {
            List<PyObject> values = bridge.callAttr(method, args).asList();
            if (values.size() < 2) {
                throw new IllegalStateException(method + " returned fewer than two values");
            }
            HookStrategy strategy = parseStrategy(values.get(0));
            T value = toJavaOrFallback(values.get(1), expectedClass, fallback);
            return new HookOutcome<>(strategy, value);
        } catch (Throwable hookError) {
            recordBridgeError(method, hookError);
            return HookOutcome.defaultValue(fallback);
        }
    }

    private void callVoid(String method, Object... args) {
        if (!isRuntimeAvailable() || !loaded) {
            return;
        }
        try {
            bridge.callAttr(method, args);
        } catch (Throwable error) {
            recordBridgeError(method, error);
        }
    }

    private static HookStrategy parseStrategy(PyObject value) {
        String name = value == null ? "DEFAULT" : value.toString();
        try {
            return HookStrategy.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return HookStrategy.DEFAULT;
        }
    }

    private boolean bridgeSucceeded(String operation, PyObject result) {
        if (result == null) {
            return false;
        }
        try {
            Object converted = result.toJava(Object.class);
            return !(converted instanceof Boolean) || (Boolean) converted;
        } catch (Throwable error) {
            recordBridgeError(operation, error);
            return false;
        }
    }

    private boolean decodeBoolean(String operation, PyObject result, boolean fallback) {
        if (result == null) {
            return fallback;
        }
        try {
            return result.toBoolean();
        } catch (Throwable error) {
            recordBridgeError(operation, error);
            return fallback;
        }
    }

    private static <T> T toJavaOrFallback(PyObject value, Class<T> clazz, T fallback) {
        if (value == null) {
            return fallback;
        }
        T converted = value.toJava(clazz);
        return converted == null ? fallback : converted;
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create " + directory);
        }
    }

    private void recordBridgeError(String operation, Throwable error) {
        FileLog.e(error);
        if (context == null) {
            return;
        }
        try {
            File directory = new File(context.getFilesDir(), "python_plugins");
            ensureDirectory(directory);
            File journal = new File(directory, "bridge-errors.log");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                    .format(new Date());
            String entry = timestamp + " " + operation + " " + error + "\n";
            try (FileOutputStream output = new FileOutputStream(journal, true)) {
                output.write(entry.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable journalError) {
            FileLog.e(journalError);
        }
    }
}
