package org.telegram.ui.Feed;

import android.annotation.SuppressLint;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Stars.StarsReactionsSheet;

import java.util.ArrayList;

class FeedReactionHandler {

    private final FeedActivity activity;

    private Runnable pendingPaidSend;
    private FeedController.FeedItem pendingPaidItem;
    private int pendingPaidAmount;
    private long pendingPaidRandomId;
    private Bulletin currentStarBulletin;

    FeedReactionHandler(FeedActivity activity) {
        this.activity = activity;
    }

    void sendReaction(FeedController.FeedItem item, TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionPaid) return;

        int account = activity.getAccount();
        MessageObject msg = item.getPrimaryMessage();
        MessagesController controller = MessagesController.getInstance(account);

        boolean nowChosen = false;
        if (msg.messageOwner.reactions != null
                && msg.messageOwner.reactions.results != null) {
            for (TLRPC.ReactionCount rc : msg.messageOwner.reactions.results) {
                if (FeedReactionsView.reactionsEqual(rc.reaction, reaction)
                        && (rc.flags & 1) != 0) {
                    nowChosen = true;
                    break;
                }
            }
        }

        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = controller.getInputPeer(item.channelId);
        req.msg_id = msg.getId();
        req.flags |= 1;
        req.reaction = new ArrayList<>();
        if (nowChosen) {
            req.reaction.add(reaction);
            req.flags |= 4;
        }

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    int pos = activity.adapter.findItemPosition(item);
                    if (pos >= 0) activity.adapter.notifyItemChanged(pos);
                });
            }
        });
    }

    void handlePaidReaction(FeedController.FeedItem item, int amount) {
        if (pendingPaidSend != null && pendingPaidItem != null && pendingPaidItem != item) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, pendingPaidRandomId);
            pendingPaidSend = null;
        }

        if (pendingPaidSend != null && pendingPaidItem == item) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            pendingPaidAmount += amount;
        } else {
            pendingPaidItem = item;
            pendingPaidAmount = amount;
            long currentTime = ConnectionsManager.getInstance(activity.getAccount())
                    .getCurrentTime();
            pendingPaidRandomId =
                    (Utilities.random.nextLong() & 0xFFFFFFFFL) | (currentTime << 32);
        }

        updatePaidReactionUI(item, amount);
        showPaidUndoBulletin(item, pendingPaidAmount);

        final long randomId = pendingPaidRandomId;
        pendingPaidSend = () -> {
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, randomId);
            pendingPaidSend = null;
            pendingPaidItem = null;
            pendingPaidAmount = 0;
        };
        AndroidUtilities.runOnUIThread(pendingPaidSend, 5000);
    }

    private void updatePaidReactionUI(FeedController.FeedItem item, int amount) {
        int pos = activity.adapter.findItemPosition(item);
        if (pos < 0) return;
        RecyclerView.ViewHolder vh =
                activity.listView.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView instanceof FeedPostCell) {
            ((FeedPostCell) vh.itemView).getReactionsView()
                    .optimisticallyAddPaid(amount);
        }
    }

    private void showPaidUndoBulletin(FeedController.FeedItem item, int totalAmount) {
        if (currentStarBulletin != null) currentStarBulletin.hide();

        String title = LocaleController.formatString(
                R.string.FeedSentStarsAnonymously, totalAmount);
        String subtitle = LocaleController.formatPluralString(
                "FeedReactedWithStars", totalAmount);

        Bulletin.TwoLineLottieLayout layout = new Bulletin.TwoLineLottieLayout(
                activity.getParentActivity(), activity.getResProvider());
        layout.setAnimation(R.raw.stars_topup, 36, 36);
        layout.titleTextView.setText(title);
        layout.subtitleTextView.setText(subtitle);

        Bulletin.UndoButton undoButton = new Bulletin.UndoButton(
                activity.getParentActivity(), true, false, activity.getResProvider());
        undoButton.setText(LocaleController.getString(R.string.Undo));
        undoButton.setUndoAction(() -> undoPaidReaction(item, pendingPaidAmount));
        layout.setButton(undoButton);

        currentStarBulletin = Bulletin.make(activity, layout, 5000);
        activity.showBulletinTop(currentStarBulletin);
    }

    private void undoPaidReaction(FeedController.FeedItem item, int amount) {
        if (pendingPaidSend != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            pendingPaidSend = null;
        }

        int pos = activity.adapter.findItemPosition(item);
        if (pos >= 0) {
            RecyclerView.ViewHolder vh =
                    activity.listView.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView instanceof FeedPostCell) {
                ((FeedPostCell) vh.itemView).getReactionsView()
                        .optimisticallyRemovePaid(amount);
            }
        }
        pendingPaidItem = null;
        pendingPaidAmount = 0;
    }

    private void doSendPaidReaction(FeedController.FeedItem item, int amount,
                                    long randomId) {
        if (item == null || amount <= 0) return;

        int account = activity.getAccount();
        MessageObject msg = item.getPrimaryMessage();
        MessagesController controller = MessagesController.getInstance(account);

        TLRPC.TL_messages_sendPaidReaction req = new TLRPC.TL_messages_sendPaidReaction();
        req.peer = controller.getInputPeer(item.channelId);
        req.msg_id = msg.getId();
        req.count = amount;
        req.random_id = randomId;
        req.flags = 0;

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (error != null) {
                        int pos = activity.adapter.findItemPosition(item);
                        if (pos >= 0) {
                            RecyclerView.ViewHolder vh =
                                    activity.listView.findViewHolderForAdapterPosition(pos);
                            if (vh != null && vh.itemView instanceof FeedPostCell) {
                                ((FeedPostCell) vh.itemView).getReactionsView()
                                        .optimisticallyRemovePaid(amount);
                            }
                        }
                        String errText = error.text != null
                                && error.text.contains("BALANCE")
                                ? "Not enough Stars" : "Error: " + error.text;
                        BulletinFactory.of(activity)
                                .createSimpleBulletin(R.drawable.star_small_inner, errText)
                                .show();
                    } else if (response instanceof TLRPC.Updates) {
                        controller.processUpdates((TLRPC.Updates) response, false);
                    }
                }));
    }

    @SuppressLint("SetTextI18n")
    void showStarAmountPicker(FeedController.FeedItem item) {
        if (activity.getParentActivity() == null) return;

        MessageObject msg = item.getPrimaryMessage();
        ArrayList<TLRPC.MessageReactor> reactors = null;

        if (msg.messageOwner.reactions != null
                && msg.messageOwner.reactions.recent_reactions != null
                && !msg.messageOwner.reactions.recent_reactions.isEmpty()) {
            reactors = new ArrayList<>();
            for (TLRPC.MessagePeerReaction mpr
                    : msg.messageOwner.reactions.recent_reactions) {
                if (mpr.reaction instanceof TLRPC.TL_reactionPaid) {
                    TLRPC.TL_messageReactor reactor = new TLRPC.TL_messageReactor();
                    reactor.peer_id = mpr.peer_id;
                    reactor.count = 1;
                    reactors.add(reactor);
                }
            }
        }

        StarsReactionsSheet sheet = new StarsReactionsSheet(
                activity.getParentActivity(), activity.getAccount(),
                item.channelId, null, msg, reactors,
                true, false, UserObject.ANONYMOUS, activity.getResProvider());

        sheet.setOnSend((peer, stars) -> {
            AndroidUtilities.runOnUIThread(
                    () -> handlePaidReaction(item, (int) (long) stars), 300);
            return Integer.MIN_VALUE;
        });

        sheet.show();
    }

    void flushPending() {
        if (pendingPaidSend != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, pendingPaidRandomId);
            pendingPaidSend = null;
            pendingPaidItem = null;
            pendingPaidAmount = 0;
        }
    }
}
