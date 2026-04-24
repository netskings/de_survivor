package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

class FeedReportHelper {

    private final FeedActivity activity;

    FeedReportHelper(FeedActivity activity) {
        this.activity = activity;
    }

    void showReportDialog(FeedController.FeedItem item) {
        if (activity.getParentActivity() == null) return;

        Theme.ResourcesProvider rp = activity.getResProvider();

        BottomSheet.Builder builder =
                new BottomSheet.Builder(activity.getParentActivity(), true, rp);
        builder.setTitle(LocaleController.getString(R.string.ReportChat), true);

        CharSequence[] items = {
                LocaleController.getString(R.string.ReportChatSpam),
                LocaleController.getString(R.string.ReportChatFakeAccount),
                LocaleController.getString(R.string.ReportChatViolence),
                LocaleController.getString(R.string.ReportChatChild),
                LocaleController.getString(R.string.ReportChatIllegalDrugs),
                LocaleController.getString(R.string.ReportChatPersonalDetails),
                LocaleController.getString(R.string.ReportChatPornography),
                LocaleController.getString(R.string.ReportChatOther)
        };

        int[] icons = {
                R.drawable.msg_clearcache,
                R.drawable.msg_report_fake,
                R.drawable.msg_report_violence,
                R.drawable.msg_block2,
                R.drawable.msg_report_drugs,
                R.drawable.msg_report_personal,
                R.drawable.msg_report_xxx,
                R.drawable.msg_report_other
        };

        int[] types = {
                AlertsCreator.REPORT_TYPE_SPAM,
                AlertsCreator.REPORT_TYPE_FAKE_ACCOUNT,
                AlertsCreator.REPORT_TYPE_VIOLENCE,
                AlertsCreator.REPORT_TYPE_CHILD_ABUSE,
                AlertsCreator.REPORT_TYPE_ILLEGAL_DRUGS,
                AlertsCreator.REPORT_TYPE_PERSONAL_DETAILS,
                AlertsCreator.REPORT_TYPE_PORNOGRAPHY,
                AlertsCreator.REPORT_TYPE_OTHER
        };

        builder.setItems(items, icons, (dialog, i) -> {
            if (types[i] == AlertsCreator.REPORT_TYPE_OTHER) {
                showReportOtherDialog(item);
            } else {
                sendReport(item, getReportReasonText(types[i]));
            }
        });

        activity.showDialog(builder.create());
    }

    private void showReportOtherDialog(FeedController.FeedItem item) {
        if (activity.getParentActivity() == null) return;

        Theme.ResourcesProvider rp = activity.getResProvider();

        org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(activity.getParentActivity(), rp);
        builder.setTitle(LocaleController.getString(R.string.ReportChatOther));

        FrameLayout container = new FrameLayout(activity.getParentActivity());
        container.setPadding(dp(24), dp(12), dp(24), 0);

        EditTextCell inputCell = new EditTextCell(
                activity.getParentActivity(),
                LocaleController.getString(R.string.ReportChatDescription),
                true,
                false,
                -1,
                rp
        );

        inputCell.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_windowBackgroundWhite, rp)));

        container.addView(inputCell,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT));

        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Send),
                (dialog, which) -> {
                    String message = inputCell.getText().toString().trim();
                    if (!message.isEmpty()) sendReport(item, message);
                });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

        org.telegram.ui.ActionBar.AlertDialog dialog = builder.create();
        activity.showDialog(dialog);

        inputCell.editText.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(inputCell.editText), 300);
    }

    private void sendReport(FeedController.FeedItem item, String reason) {
        if (item == null) return;

        int account = activity.getAccount();

        TLRPC.TL_messages_report req = new TLRPC.TL_messages_report();
        req.peer = MessagesController.getInstance(account)
                .getInputPeer(item.channelId);
        for (MessageObject m : item.messages) req.id.add(m.getId());
        req.option = new byte[0];
        req.message = reason != null ? reason : "";

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    Bulletin b = BulletinFactory.of(activity)
                            .createSimpleBulletin(R.drawable.msg_report,
                                    LocaleController.getString(R.string.ReportChatSent));
                    activity.showBulletinTop(b);
                }));
    }

    private String getReportReasonText(int type) {
        switch (type) {
            case AlertsCreator.REPORT_TYPE_SPAM:             return "Spam";
            case AlertsCreator.REPORT_TYPE_FAKE_ACCOUNT:     return "Fake account";
            case AlertsCreator.REPORT_TYPE_VIOLENCE:         return "Violence";
            case AlertsCreator.REPORT_TYPE_CHILD_ABUSE:      return "Child abuse";
            case AlertsCreator.REPORT_TYPE_ILLEGAL_DRUGS:    return "Illegal drugs";
            case AlertsCreator.REPORT_TYPE_PERSONAL_DETAILS: return "Personal details";
            case AlertsCreator.REPORT_TYPE_PORNOGRAPHY:      return "Pornography";
            default:                                         return "";
        }
    }
}