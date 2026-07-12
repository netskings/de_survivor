package com.exteragram.messenger.plugins.ui;

import com.exteragram.messenger.plugins.Plugin;

/** Legacy constructor facade. */
public class PluginSettingsActivity extends org.telegram.ui.PluginSettingsActivity {
    public PluginSettingsActivity(Plugin plugin) {
        super(plugin.id, plugin.name);
    }
}
