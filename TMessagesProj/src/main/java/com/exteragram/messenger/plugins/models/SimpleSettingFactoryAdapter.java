package com.exteragram.messenger.plugins.models;

import android.content.Context;
import android.view.View;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Native UItemFactory peer for the high-level Python SimpleSettingFactory. */
public final class SimpleSettingFactoryAdapter extends CustomSetting.Factory {
    private static final ConcurrentHashMap<String, Set<SimpleSettingFactoryAdapter>> pluginFactories =
            new ConcurrentHashMap<>();

    private final String pluginId;
    private final PyObject createView;
    private final PyObject bindView;
    private final boolean clickable;
    private final boolean shadow;
    private final PyObject createItem;
    private final PyObject onClick;
    private final PyObject onLongClick;
    private final PyObject attachedView;
    private final PyObject equalsHandler;
    private final PyObject contentEqualsHandler;

    public SimpleSettingFactoryAdapter(
            PyObject createView,
            PyObject bindView,
            boolean clickable,
            boolean shadow,
            PyObject createItem,
            PyObject onClick,
            PyObject onLongClick,
            PyObject attachedView,
            PyObject equalsHandler,
            PyObject contentEqualsHandler,
            String pluginId
    ) {
        this.createView = createView;
        this.bindView = bindView;
        this.clickable = clickable;
        this.shadow = shadow;
        this.createItem = createItem;
        this.onClick = onClick;
        this.onLongClick = onLongClick;
        this.attachedView = attachedView;
        this.equalsHandler = equalsHandler;
        this.contentEqualsHandler = contentEqualsHandler;
        this.pluginId = pluginId;
        UItem.UItemFactory.setupInstance(this);
        if (pluginId != null && !pluginId.isEmpty()) {
            pluginFactories.computeIfAbsent(
                    pluginId, ignored -> ConcurrentHashMap.newKeySet()
            ).add(this);
        }
    }

    @Override
    public View createView(
            Context context,
            RecyclerListView listView,
            int currentAccount,
            int classGuid,
            Theme.ResourcesProvider resourcesProvider
    ) {
        PyObject result = call(
                createView, context, listView, currentAccount, classGuid, resourcesProvider
        );
        if (notNone(result)) {
            try {
                View view = result.toJava(View.class);
                if (view != null) return view;
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        return new View(context);
    }

    @Override
    public void bindView(
            View view,
            UItem item,
            boolean divider,
            UniversalAdapter adapter,
            UniversalRecyclerView listView
    ) {
        call(bindView, view, item, divider, adapter, listView);
    }

    @Override
    public boolean isClickable() {
        return clickable;
    }

    @Override
    public boolean isShadow() {
        return shadow;
    }

    @Override
    public UItem create(Plugin plugin, CustomSetting setting, Object args) {
        PyObject result = call(createItem, plugin, setting, args);
        if (notNone(result)) {
            try {
                UItem item = result.toJava(UItem.class);
                if (item != null) return item;
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        return super.create(plugin, setting, args);
    }

    @Override
    public void onClick(Plugin plugin, UItem item, View view) {
        call(onClick, plugin, item, view);
    }

    @Override
    public boolean onLongClick(Plugin plugin, UItem item, View view) {
        PyObject result = call(onLongClick, plugin, item, view);
        return booleanResult(result, false);
    }

    @Override
    public void attachedView(RecyclerListView listView, View view, UItem item) {
        call(attachedView, listView, view, item);
    }

    @Override
    public boolean equals(UItem first, UItem second) {
        PyObject result = call(equalsHandler, first, second);
        return booleanResult(result, super.equals(first, second));
    }

    @Override
    public boolean contentsEquals(UItem first, UItem second) {
        PyObject result = call(contentEqualsHandler, first, second);
        return booleanResult(result, super.contentsEquals(first, second));
    }

    private static PyObject call(PyObject callback, Object... args) {
        if (!notNone(callback)) return null;
        try {
            return callback.call(args);
        } catch (Throwable error) {
            FileLog.e(error);
            return null;
        }
    }

    private static boolean notNone(PyObject value) {
        if (value == null) return false;
        try {
            return value.toJava(Object.class) != null;
        } catch (Throwable error) {
            FileLog.e(error);
            return false;
        }
    }

    private static boolean booleanResult(PyObject value, boolean fallback) {
        if (!notNone(value)) return fallback;
        try {
            return value.toBoolean();
        } catch (Throwable error) {
            FileLog.e(error);
            return fallback;
        }
    }

    public void dispose() {
        UItem.UItemFactory.unregisterInstance(this);
        if (pluginId == null || pluginId.isEmpty()) return;
        Set<SimpleSettingFactoryAdapter> factories = pluginFactories.get(pluginId);
        if (factories != null) {
            factories.remove(this);
            if (factories.isEmpty()) {
                pluginFactories.remove(pluginId, factories);
            }
        }
    }

    public static void disposePlugin(String pluginId) {
        if (pluginId == null) return;
        Set<SimpleSettingFactoryAdapter> factories = pluginFactories.remove(pluginId);
        if (factories == null) return;
        for (SimpleSettingFactoryAdapter factory : factories) {
            UItem.UItemFactory.unregisterInstance(factory);
        }
        factories.clear();
    }
}
