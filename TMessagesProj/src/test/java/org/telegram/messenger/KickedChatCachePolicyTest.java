package org.telegram.messenger;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KickedChatCachePolicyTest {

    @Test
    public void keepsForbiddenChatAndChannel() {
        assertTrue(KickedChatCachePolicy.shouldKeep(true, new TLRPC.TL_chatForbidden()));
        assertTrue(KickedChatCachePolicy.shouldKeep(true, new TLRPC.TL_channelForbidden()));
    }

    @Test
    public void keepsExplicitKickAndViewMessagesBan() {
        TLRPC.Chat kicked = new TLRPC.TL_chat();
        kicked.kicked = true;
        assertTrue(KickedChatCachePolicy.shouldKeep(true, kicked));

        TLRPC.Chat banned = new TLRPC.TL_channel();
        banned.banned_rights = new TLRPC.TL_chatBannedRights();
        banned.banned_rights.view_messages = true;
        assertTrue(KickedChatCachePolicy.shouldKeep(true, banned));
    }

    @Test
    public void doesNotKeepVoluntaryLeaveOrDisabledPolicy() {
        TLRPC.Chat left = new TLRPC.TL_channel();
        left.left = true;
        assertFalse(KickedChatCachePolicy.shouldKeep(true, left));

        TLRPC.Chat forbidden = new TLRPC.TL_channelForbidden();
        assertFalse(KickedChatCachePolicy.shouldKeep(false, forbidden));
        assertFalse(KickedChatCachePolicy.shouldKeep(true, null));
    }
}
