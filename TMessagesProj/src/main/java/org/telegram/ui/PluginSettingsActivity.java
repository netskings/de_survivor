package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;
import java.util.Map;

/** Renders the stable ui.settings dataclasses without executing UI code during list diffing. */
public class PluginSettingsActivity extends UniversalFragment
        implements PluginManager.SettingsReloadListener {

    private static final int SETTING_ID = 1000;

    private final String pluginId;
    private final String pluginName;
    private final String parentToken;
    private final String pageTitle;
    private final PluginManager pluginManager = PluginManager.getInstance();
    private List<SettingRow> settings = Collections.emptyList();
    private final Map<UItem, SettingRow> rowsByItem = new IdentityHashMap<>();
    private boolean firstResume = true;

    public PluginSettingsActivity(String pluginId, String pluginName) {
        this(pluginId, pluginName, null, pluginName);
    }

    private PluginSettingsActivity(
            String pluginId,
            String pluginName,
            String parentToken,
            String pageTitle
    ) {
        this.pluginId = pluginId;
        this.pluginName = pluginName;
        this.parentToken = parentToken;
        this.pageTitle = pageTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        reloadSettings();
        pluginManager.addSettingsReloadListener(this);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        pluginManager.removeSettingsReloadListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
        } else {
            reloadSettings();
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onPluginSettingsReload(String changedPluginId) {
        if (!pluginId.equals(changedPluginId)) {
            return;
        }
        reloadSettings();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return pageTitle;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rowsByItem.clear();
        if (settings.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PythonPluginsNoSettings)));
            return;
        }
        for (int rowIndex = 0; rowIndex < settings.size(); rowIndex++) {
            SettingRow setting = settings.get(rowIndex);
            int itemId = SETTING_ID + rowIndex;
            UItem item;
            switch (setting.kind) {
                case "header":
                    item = UItem.asHeader(setting.text);
                    break;
                case "divider":
                    item = UItem.asShadow(setting.text);
                    break;
                case "switch":
                    item = UItem.asButtonCheck(itemId, setting.text, setting.subtext)
                            .setChecked(setting.booleanValue());
                    break;
                case "selector":
                    item = UItem.asButton(
                            itemId,
                            setting.text,
                            setting.selectedText()
                    );
                    break;
                case "input":
                case "edittext":
                case "edit_text":
                    item = UItem.asButton(
                            itemId,
                            setting.text.isEmpty() ? setting.hint : setting.text,
                            setting.stringValue().isEmpty()
                                    ? setting.subtext : setting.stringValue()
                    );
                    break;
                case "custom":
                    item = setting.customItem(this);
                    if (item == null) {
                        item = UItem.asShadow(LocaleController.getString(R.string.PythonPluginsCustomSetting));
                    }
                    break;
                case "text":
                default:
                    item = UItem.asButton(itemId, setting.text, setting.subtext);
                    if (setting.accent) {
                        item.accent();
                    }
                    if (setting.red) {
                        item.red();
                    }
                    break;
            }
            int iconResId = resolveDrawable(setting.icon);
            if (iconResId != 0) {
                item.iconResId = iconResId;
                if (item.viewType == UniversalAdapter.VIEW_TYPE_TEXT_CHECK) {
                    item.viewType = UniversalAdapter.VIEW_TYPE_ICON_TEXT_CHECK;
                }
            }
            if ("custom".equals(setting.kind)
                    && (setting.hasClick || setting.hasLongClick || setting.hasSubpage)) {
                item.selectable = true;
            }
            if (!"custom".equals(setting.kind)) {
                item.object = setting;
            }
            rowsByItem.put(item, setting);
            items.add(item);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Object rowObject = rowsByItem.get(item);
        if (!(rowObject instanceof SettingRow)) {
            rowObject = item.object instanceof SettingRow ? item.object : item.object2;
        }
        if (!(rowObject instanceof SettingRow)) {
            return;
        }
        SettingRow setting = (SettingRow) rowObject;
        if (setting.hasSubpage) {
            pluginManager.clickSetting(
                    pluginId,
                    setting.token,
                    new PluginManager.ViewForPlugin(view),
                    item,
                    false
            );
            presentFragment(new PluginSettingsActivity(
                    pluginId,
                    pluginName,
                    setting.token,
                    setting.text.isEmpty() ? pluginName : setting.text
            ));
            return;
        }
        switch (setting.kind) {
            case "switch":
                changeSetting(setting, !setting.booleanValue());
                break;
            case "selector":
                showSelector(setting);
                break;
            case "input":
            case "edittext":
            case "edit_text":
                showInput(setting);
                break;
            case "text":
            case "custom":
                pluginManager.clickSetting(
                        pluginId,
                        setting.token,
                        new PluginManager.ViewForPlugin(view),
                        item,
                        false
                );
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        Object rowObject = rowsByItem.get(item);
        if (!(rowObject instanceof SettingRow)) {
            rowObject = item.object instanceof SettingRow ? item.object : item.object2;
        }
        if (!(rowObject instanceof SettingRow)) {
            return false;
        }
        SettingRow setting = (SettingRow) rowObject;
        return pluginManager.clickSetting(
                pluginId,
                setting.token,
                new PluginManager.ViewForPlugin(view),
                item,
                true
        );
    }

    private void showSelector(SettingRow setting) {
        if (getContext() == null || setting.items.isEmpty()) {
            return;
        }
        CharSequence[] values = setting.items.toArray(new CharSequence[0]);
        new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(setting.text)
                .setItems(values, (dialog, which) -> changeSetting(setting, which))
                .show();
    }

    private void showInput(SettingRow setting) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        FrameLayout container = new FrameLayout(activity);
        EditText input = new EditText(activity);
        input.setText(setting.stringValue());
        input.setHint(setting.hint);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText));
        input.setSingleLine(!setting.multiline);
        input.setInputType(setting.multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT);
        if (setting.maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(setting.maxLength)});
        }
        container.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(setting.text.isEmpty() ? setting.hint : setting.text)
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.PythonPluginsSave), null)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    changeSetting(setting, input.getText().toString());
                    dialog.dismiss();
                }));
        showDialog(dialog);
        input.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(input), 150);
    }

    private void changeSetting(SettingRow setting, Object value) {
        pluginManager.changeSetting(pluginId, setting.token, value);
        reloadSettings();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void reloadSettings() {
        ArrayList<SettingRow> rows = new ArrayList<>();
        List<PyObject> serialized = parentToken == null
                ? pluginManager.getSerializedSettings(pluginId)
                : pluginManager.getSerializedSubSettings(pluginId, parentToken);
        for (int index = 0; index < serialized.size(); index++) {
            try {
                rows.add(new SettingRow(index, serialized.get(index)));
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        settings = rows;
    }

    private static int resolveDrawable(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        try {
            return R.drawable.class.getField(name).getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final class SettingRow {
        final String token;
        final PyObject raw;
        final String kind;
        final String text;
        final String subtext;
        final String hint;
        final String icon;
        final Object value;
        final List<String> items;
        final boolean accent;
        final boolean red;
        final boolean multiline;
        final int maxLength;
        final boolean hasSubpage;
        final boolean hasClick;
        final boolean hasLongClick;

        SettingRow(int fallbackIndex, PyObject raw) {
            this.raw = raw;
            Object tokenValue = object(raw, "index");
            this.token = tokenValue == null ? String.valueOf(fallbackIndex) : String.valueOf(tokenValue);
            this.kind = string(raw, "kind").replace("_", "").toLowerCase(Locale.US);
            this.text = string(raw, "text");
            this.subtext = string(raw, "subtext");
            this.hint = string(raw, "hint");
            this.icon = string(raw, "icon");
            this.value = object(raw, "value");
            this.accent = bool(raw, "accent");
            this.red = bool(raw, "red");
            this.multiline = bool(raw, "multiline");
            this.maxLength = integer(raw, "max_length", 0);
            this.hasSubpage = bool(raw, "has_subpage");
            this.hasClick = bool(raw, "has_click");
            this.hasLongClick = bool(raw, "has_long_click");
            ArrayList<String> parsedItems = new ArrayList<>();
            PyObject itemsValue = raw.get("items");
            if (itemsValue != null && object(raw, "items") != null) {
                for (PyObject item : itemsValue.asList()) {
                    parsedItems.add(item.toString());
                }
            }
            this.items = parsedItems;
        }

        boolean booleanValue() {
            return value instanceof Boolean && (Boolean) value;
        }

        String stringValue() {
            return value == null ? "" : String.valueOf(value);
        }

        String selectedText() {
            int selected = value instanceof Number ? ((Number) value).intValue() : 0;
            return selected >= 0 && selected < items.size() ? items.get(selected) : "";
        }

        UItem customItem(PluginSettingsActivity owner) {
            try {
                UItem createdItem = owner.pluginManager.createCustomSettingItem(
                        owner.pluginId, token
                );
                if (createdItem != null) {
                    return createdItem;
                }
                PyObject item = raw.get("item");
                if (item != null && item.toJava(Object.class) != null) {
                    UItem converted = item.toJava(UItem.class);
                    if (converted != null) {
                        return converted;
                    }
                }
                PyObject view = raw.get("view");
                if (view != null && view.toJava(Object.class) != null) {
                    View converted = view.toJava(View.class);
                    if (converted != null) {
                        return UItem.asCustom(converted);
                    }
                }
                View created = owner.pluginManager.createCustomSettingView(
                        owner.pluginId,
                        token,
                        owner.getContext(),
                        owner.listView,
                        owner.currentAccount,
                        owner.classGuid,
                        owner.getResourceProvider()
                );
                if (created != null) {
                    return UItem.asCustom(created);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static String string(PyObject object, String key) {
            Object value = object(object, key);
            return value == null ? "" : String.valueOf(value);
        }

        private static boolean bool(PyObject object, String key) {
            Object value = object(object, key);
            return value instanceof Boolean && (Boolean) value;
        }

        private static int integer(PyObject object, String key, int fallback) {
            Object value = object(object, key);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        private static Object object(PyObject object, String key) {
            PyObject value = object.get(key);
            return value == null ? null : value.toJava(Object.class);
        }
    }
}
