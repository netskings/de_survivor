package com.exteragram.messenger.plugins;

import org.telegram.messenger.plugins.PluginDescriptor;

import java.util.List;

/** Read-only legacy Java model exposed to Python through Chaquopy. */
public class Plugin {
    public final String id;
    public final String name;
    public final String description;
    public final String author;
    public final String version;
    public final String icon;
    public final String minVersion;
    public final String appVersion;
    public final String sdkVersion;
    public final List<String> requirements;
    public final String path;
    public final String errorMessage;
    public boolean enabled;
    public boolean initialized;

    public Plugin(PluginDescriptor descriptor) {
        id = descriptor.id;
        name = descriptor.name;
        description = descriptor.description;
        author = descriptor.author;
        version = descriptor.version;
        icon = descriptor.icon;
        appVersion = descriptor.appVersion;
        minVersion = descriptor.minVersion;
        sdkVersion = descriptor.sdkVersion;
        requirements = descriptor.requirements;
        path = descriptor.path;
        errorMessage = descriptor.errorMessage;
        enabled = descriptor.enabled;
        initialized = descriptor.initialized;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
