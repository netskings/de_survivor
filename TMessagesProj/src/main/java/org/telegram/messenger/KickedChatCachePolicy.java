package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Custom.CustomSettings;

/**
 * Central policy for dialogs which only exist locally after the current user
 * has been removed from a group or channel.
 */
public final class KickedChatCachePolicy {

    private KickedChatCachePolicy() {
    }

    public static boolean shouldKeep(TLRPC.Chat chat) {
        return shouldKeep(CustomSettings.keepKickedChatsCache(), chat);
    }

    static boolean shouldKeep(boolean enabled, TLRPC.Chat chat) {
        if (!enabled || chat == null) {
            return false;
        }
        return chat instanceof TLRPC.TL_chatForbidden
                || chat instanceof TLRPC.TL_channelForbidden
                || chat.kicked
                || chat.banned_rights != null && chat.banned_rights.view_messages;
    }
}
