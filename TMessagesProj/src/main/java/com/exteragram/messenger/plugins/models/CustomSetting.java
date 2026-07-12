package com.exteragram.messenger.plugins.models;

import android.content.Context;
import android.view.View;

import com.exteragram.messenger.plugins.Plugin;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;

/** Legacy Java model used by custom plugin setting factories. */
public final class CustomSetting {
    public final Object setting;
    public final Object args;

    public CustomSetting(Object setting, Object args) {
        this.setting = setting;
        this.args = args;
    }

    public static abstract class Factory extends UItem.UItemFactory<View> {
        public UItem create(Plugin plugin, CustomSetting setting, Object args) {
            UItem item = UItem.ofFactory(this);
            item.object = plugin;
            item.object2 = setting;
            return item;
        }

        public void onClick(Plugin plugin, UItem item, View view) {
        }

        public boolean onLongClick(Plugin plugin, UItem item, View view) {
            return false;
        }
    }
}
