package org.telegram.messenger.plugins;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PythonPluginRuntimeTest {

    @Test
    public void python311AndLegacyModulesAreEmbedded() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
        Python python = Python.getInstance();
        String version = python.getModule("sys").get("version").toString();
        assertTrue("Expected Python 3.11, got " + version, version.startsWith("3.11."));

        String[] modules = {
                "requests",
                "packaging",
                "typing_extensions",
                "_sdk_version",
                "base_plugin",
                "android_utils",
                "client_utils",
                "file_utils",
                "hook_utils",
                "dev_server",
                "plugin_settings",
                "plugins_manager",
                "markdown_utils",
                "ui.settings",
                "ui.alert",
                "ui.dialog",
                "ui.bulletin",
                "extera_utils.metadata_parser",
                "extera_utils.get_caller",
                "extera_utils.classes",
                "extera_utils.text_formatting",
                "plugin_api_v2"
        };
        for (String module : modules) {
            python.getModule(module);
        }
    }

    @Test
    public void legacyVerticalSliceRunsInsideChaquopy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
        File root = new File(context.getCacheDir(), "python-plugin-test-" + System.nanoTime());
        File plugins = new File(root, "plugins");
        File state = new File(root, "state");
        assertTrue(plugins.mkdirs());
        assertTrue(state.mkdirs());

        File source = new File(root, "embedded.plugin");
        write(source,
                "import requests\n" +
                "from base_plugin import BasePlugin, HookResult, HookStrategy\n" +
                "from client_utils import RequestCallback\n" +
                "from ui.settings import Header, Switch, SimpleSettingFactory\n" +
                "__id__='embedded_legacy'\n" +
                "__name__='Embedded legacy'\n" +
                "__min_version__='>=1.0'\n" +
                "class P(BasePlugin):\n" +
                " def __init__(self):\n" +
                "  super().__init__()\n" +
                "  if self.id != 'embedded_legacy': raise RuntimeError('metadata missing in constructor')\n" +
                "  self.set_setting('constructor_owner', self.id)\n" +
                "  self.callback = RequestCallback(lambda response, error: None)\n" +
                "  self.factory = SimpleSettingFactory(lambda *args: None, lambda *args: None)\n" +
                " def on_plugin_load(self):\n" +
                "  self.add_on_send_message_hook()\n" +
                "  self.add_hook('TL_messages_sendMessage')\n" +
                "  self.add_hook('TL_updateUserStatus')\n" +
                "  self.add_hook('TL_updates')\n" +
                " def on_send_message_hook(self, account, params):\n" +
                "  if account == 7:\n" +
                "   params.message = 'modified by Python'\n" +
                "   return HookResult(HookStrategy.MODIFY, params=params)\n" +
                "  return HookResult(HookStrategy.BLOCK)\n" +
                " def pre_request_hook(self, name, account, request):\n" +
                "  request.message = 'request modified'\n" +
                "  return HookResult(HookStrategy.MODIFY, request=request)\n" +
                " def on_update_hook(self, name, account, update):\n" +
                "  update.user_id = 42\n" +
                "  return HookResult(HookStrategy.MODIFY, update=update)\n" +
                " def on_updates_hook(self, name, account, updates):\n" +
                "  updates.date = 77\n" +
                "  return HookResult(HookStrategy.MODIFY, updates=updates)\n" +
                " def create_settings(self): return [Header('General'), Switch('on', 'On', True), self.factory('payload')]\n");

        PyObject bridge = Python.getInstance().getModule("plugin_runtime.android_bridge");
        bridge.callAttr("initialize", plugins.getAbsolutePath(), state.getAbsolutePath(), "99.0", "1.4.3.10");
        PyObject installed = bridge.callAttr("install", source.getAbsolutePath());
        assertTrue("embedded_legacy".equals(installed.get("id").toString()));
        bridge.callAttr("set_enabled", "embedded_legacy", true);

        List<PyObject> hook = bridge.callAttr("dispatch_send", 0, new Object()).asList();
        assertTrue("CANCEL".equals(hook.get(0).toString()));
        SendMessagesHelper.SendMessageParams params =
                SendMessagesHelper.SendMessageParams.of("original", 1L);
        List<PyObject> modified = bridge.callAttr("dispatch_send", 7, params).asList();
        assertTrue("MODIFY".equals(modified.get(0).toString()));
        SendMessagesHelper.SendMessageParams modifiedParams =
                modified.get(1).toJava(SendMessagesHelper.SendMessageParams.class);
        assertTrue("modified by Python".equals(modifiedParams.message));

        TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
        request.message = "original request";
        List<PyObject> pre = bridge.callAttr(
                "dispatch_pre_request", "TL_messages_sendMessage", 0, request
        ).asList();
        assertTrue("MODIFY".equals(pre.get(0).toString()));
        assertTrue("request modified".equals(request.message));

        TLRPC.TL_updateUserStatus update = new TLRPC.TL_updateUserStatus();
        bridge.callAttr("dispatch_update", "TL_updateUserStatus", 0, update);
        assertTrue(update.user_id == 42L);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        bridge.callAttr("dispatch_updates", "TL_updates", 0, updates);
        assertTrue(updates.date == 77);
        List<PyObject> settings = bridge.callAttr("serialize_settings", "embedded_legacy").asList();
        assertTrue(settings.size() == 3);
        assertTrue("switch".equals(settings.get(1).get("kind").toString()));
        assertTrue("custom".equals(settings.get(2).get("kind").toString()));
        UItem customItem = bridge.callAttr("create_custom_item", "embedded_legacy", 2)
                .toJava(UItem.class);
        assertTrue(customItem != null);
        assertTrue("embedded_legacy".equals(
                bridge.callAttr("legacy_preferences_all")
                        .get("plugin_setting_embedded_legacy_constructor_owner")
                        .toString()
        ));
        bridge.callAttr("shutdown");
    }

    private static void write(File target, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
