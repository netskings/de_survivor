package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

@SuppressLint("ViewConstructor")
public class FeedForwardView extends LinearLayout {

    public interface OnForwardClickListener {
        void onForwardClick(long channelId, int messageId);
    }

    private final TextView nameView;
    private final BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable;

    private long fwdChannelId;
    private int fwdMessageId;
    private OnForwardClickListener listener;

    public FeedForwardView(Context context, Theme.ResourcesProvider resourceProvider) {
        super(context);

        int greenColor = Theme.getColor(Theme.key_avatar_nameInMessageGreen, resourceProvider);

        setOrientation(HORIZONTAL);
        setPadding(0, dp(8), 0, 0);
        setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        setOnClickListener(v -> {
            if (listener != null && fwdChannelId != 0)
                listener.onForwardClick(fwdChannelId, fwdMessageId);
        });

        View border = new View(context);
        border.setBackgroundColor(greenColor);
        addView(border, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(8), dp(2), dp(4), dp(2));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(greenColor);
        label.setText(getString(R.string.ForwardedFrom));
        content.addView(label, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        avatarDrawable = new AvatarDrawable();
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(9));
        avatarView.setVisibility(GONE);
        nameRow.addView(avatarView, LayoutHelper.createLinear(
                18, 18, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(greenColor);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(nameView, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f));

        content.addView(nameRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(content, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
    }

    public void setOnForwardClickListener(OnForwardClickListener l) { listener = l; }

    @SuppressLint("SetTextI18n")
    public void setData(TLRPC.Message raw, MessagesController controller) {
        fwdChannelId = 0;
        fwdMessageId = 0;
        avatarView.setVisibility(GONE);

        if (raw.fwd_from == null) {
            setVisibility(GONE);
            return;
        }

        TLRPC.MessageFwdHeader fwd = raw.fwd_from;
        String name = resolveName(fwd, controller);
        if (fwd.channel_post != 0) fwdMessageId = fwd.channel_post;

        if (name != null) {
            nameView.setText(name);
            setupAvatar(fwd, controller);
            setVisibility(VISIBLE);
        } else {
            setVisibility(GONE);
        }
    }

    public void clear() {
        fwdChannelId = 0;
        fwdMessageId = 0;
        avatarView.setVisibility(GONE);
        setVisibility(GONE);
    }

    private String resolveName(TLRPC.MessageFwdHeader fwd, MessagesController ctrl) {
        String name = null;
        if (fwd.from_id != null) {
            if (fwd.from_id.channel_id != 0) {
                fwdChannelId = fwd.from_id.channel_id;
                TLRPC.Chat c = ctrl.getChat(fwdChannelId);
                if (c != null) name = c.title;
            } else if (fwd.from_id.user_id != 0) {
                TLRPC.User u = ctrl.getUser(fwd.from_id.user_id);
                if (u != null) {
                    name = u.first_name;
                    if (u.last_name != null && !u.last_name.isEmpty())
                        name += " " + u.last_name;
                }
            } else if (fwd.from_id.chat_id != 0) {
                TLRPC.Chat c = ctrl.getChat(fwd.from_id.chat_id);
                if (c != null) name = c.title;
            }
        }
        if (name == null && fwd.from_name != null && !fwd.from_name.isEmpty())
            name = fwd.from_name;
        return name;
    }

    private void setupAvatar(TLRPC.MessageFwdHeader fwd, MessagesController ctrl) {
        if (fwd.from_id == null) { avatarView.setVisibility(GONE); return; }

        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        if (fwd.from_id.channel_id != 0) chat = ctrl.getChat(fwd.from_id.channel_id);
        else if (fwd.from_id.chat_id != 0) chat = ctrl.getChat(fwd.from_id.chat_id);
        else if (fwd.from_id.user_id != 0) user = ctrl.getUser(fwd.from_id.user_id);

        if (chat != null) {
            avatarDrawable.setInfo(chat);
            if (chat.photo != null && chat.photo.photo_small != null)
                avatarView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL),
                        "18_18", avatarDrawable, chat);
            else avatarView.setImageDrawable(avatarDrawable);
            avatarView.setVisibility(VISIBLE);
        } else if (user != null) {
            avatarDrawable.setInfo(user);
            if (user.photo != null && user.photo.photo_small != null)
                avatarView.setImage(ImageLocation.getForUser(user, ImageLocation.TYPE_SMALL),
                        "18_18", avatarDrawable, user);
            else avatarView.setImageDrawable(avatarDrawable);
            avatarView.setVisibility(VISIBLE);
        } else {
            avatarView.setVisibility(GONE);
        }
    }
}
