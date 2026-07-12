package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginDescriptor;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Native manager for local Python plugin files. */
public class PluginsActivity extends UniversalFragment {

    private static final int ITEM_SAFE_MODE = 1;
    private static final int ITEM_IMPORT = 2;
    private static final int ITEM_PLUGIN = 1000;
    private static final int ITEM_PLUGIN_ENABLED = 2000;
    private static final int REQUEST_IMPORT = 7301;

    private final PluginManager pluginManager = PluginManager.getInstance();
    private List<PluginDescriptor> plugins = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        refreshPlugins();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPlugins();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PythonPlugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PythonPluginsSafety)));
        items.add(UItem.asCheck(ITEM_SAFE_MODE, LocaleController.getString(R.string.PythonPluginsSafeMode))
                .setChecked(pluginManager.isSafeModeConfigured()));
        String safeModeInfo = pluginManager.isSafeMode()
                ? LocaleController.getString(R.string.PythonPluginsSafeModeActive) + " " + pluginManager.getSafeModeReason()
                : LocaleController.getString(R.string.PythonPluginsSafeModeInfo);
        items.add(UItem.asShadow(safeModeInfo));

        items.add(UItem.asButton(
                ITEM_IMPORT,
                R.drawable.msg_add,
                LocaleController.getString(R.string.PythonPluginsInstall)
        ));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PythonPluginsInstallInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PythonPluginsInstalled)));
        if (!pluginManager.isRuntimeAvailable()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PythonPluginsUnavailableUntilRestart)));
            return;
        }
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PythonPluginsEmpty)));
            return;
        }

        for (int index = 0; index < plugins.size(); index++) {
            PluginDescriptor plugin = plugins.get(index);
            String value = plugin.version;
            if (!plugin.errorMessage.isEmpty()) {
                value = LocaleController.getString(R.string.PythonPluginsError);
            }
            UItem details = UItem.asButton(ITEM_PLUGIN + index, plugin.name, value);
            details.object = plugin;
            items.add(details);

            String enabledText = plugin.enabled
                    ? LocaleController.getString(R.string.PythonPluginsEnabled)
                    : LocaleController.getString(R.string.PythonPluginsDisabled);
            UItem enabled = UItem.asCheck(ITEM_PLUGIN_ENABLED + index, enabledText)
                    .setChecked(plugin.enabled);
            enabled.object = plugin;
            items.add(enabled);

            String info = plugin.description;
            if (!plugin.errorMessage.isEmpty()) {
                info = plugin.errorMessage;
            }
            items.add(UItem.asShadow(info));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ITEM_SAFE_MODE) {
            boolean enabled = !pluginManager.isSafeModeConfigured();
            pluginManager.setSafeMode(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            Toast.makeText(getContext(), R.string.PythonPluginsRestartRequired, Toast.LENGTH_LONG).show();
            return;
        }
        if (item.id == ITEM_IMPORT) {
            if (!pluginManager.isRuntimeAvailable()) {
                Toast.makeText(getContext(), R.string.PythonPluginsUnavailableUntilRestart, Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
            return;
        }
        if (!(item.object instanceof PluginDescriptor)) {
            return;
        }
        PluginDescriptor plugin = (PluginDescriptor) item.object;
        if (item.id >= ITEM_PLUGIN_ENABLED) {
            boolean enabled = !plugin.enabled;
            Context applicationContext = getContext() == null
                    ? null : getContext().getApplicationContext();
            Utilities.globalQueue.postRunnable(() -> {
                boolean success = pluginManager.setPluginEnabled(plugin.id, enabled);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!success && applicationContext != null) {
                        Toast.makeText(applicationContext, R.string.PythonPluginsOperationFailed, Toast.LENGTH_LONG).show();
                    }
                    if (getContext() == null) {
                        return;
                    }
                    refreshPlugins();
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                });
            });
        } else {
            presentFragment(new PluginSettingsActivity(plugin.id, plugin.name));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        Context context = getContext();
        if (uri == null || context == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> importPlugin(context, uri));
    }

    private void importPlugin(Context context, Uri uri) {
        Context applicationContext = context.getApplicationContext();
        File temporary = new File(applicationContext.getCacheDir(), "plugin-import-" + System.nanoTime() + ".plugin");
        boolean success = false;
        try (InputStream input = applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IllegalArgumentException("Unable to open selected file");
            }
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
            success = pluginManager.installPlugin(temporary);
        } catch (Throwable ignored) {
            success = false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
        final boolean installed = success;
        AndroidUtilities.runOnUIThread(() -> {
            Toast.makeText(
                    applicationContext,
                    installed ? R.string.PythonPluginsInstalledSuccess : R.string.PythonPluginsOperationFailed,
                    Toast.LENGTH_LONG
            ).show();
            if (getContext() == null) {
                return;
            }
            refreshPlugins();
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void refreshPlugins() {
        plugins = pluginManager.listPlugins();
    }
}
