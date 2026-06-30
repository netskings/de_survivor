/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppStartReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "org.telegram.start";

    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_START.equals(action)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    SharedConfig.loadConfig();
                    if (SharedConfig.passcodeHash.length() > 0) {
                        SharedConfig.appLocked = true;
                        SharedConfig.saveConfig();
                    }
                }
                ApplicationLoader.startPushService();
            });
        }
    }
}
