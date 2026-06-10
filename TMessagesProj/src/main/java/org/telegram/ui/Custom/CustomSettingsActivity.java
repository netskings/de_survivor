package org.telegram.ui.Custom;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Feed.FeedSettingsActivity;

public class CustomSettingsActivity extends BaseFragment {

    private int rowCount;
    private int adsHeaderRow;
    private int hideAdsRow;
    private int hideAdsInfoRow;
    private int proxyHeaderRow;
    private int hideProxySponsorRow;
    private int hideProxySponsorInfoRow;

    private int feedHeaderRow;
    private int feedSettingsRow;
    private int feedInfoRow;

    private int ghostHeaderRow;
    private int hideOnlineStatusRow;
    private int hideOnlineStatusInfoRow;
    private int keepLastSeenUpdatedInGhostModeRow;
    private int keepLastSeenUpdatedInGhostModeInfoRow;

    private int restrictionsHeaderRow;
    private int antiRecallRow;
    private int antiRecallInfoRow;
    private int saveTemporaryMediaRow;
    private int saveTemporaryMediaInfoRow;
    private int keepTemporaryMediaInChatRow;
    private int keepTemporaryMediaInChatInfoRow;
    private int keepKickedChatsCacheRow;
    private int keepKickedChatsCacheInfoRow;
    private int bypassContentProtectionRow;
    private int bypassContentProtectionInfoRow;

    @Override
    public boolean onFragmentCreate() {
        rowCount = 0;
        adsHeaderRow = rowCount++;
        hideAdsRow = rowCount++;
        hideAdsInfoRow = rowCount++;
        proxyHeaderRow = rowCount++;
        hideProxySponsorRow = rowCount++;
        hideProxySponsorInfoRow = rowCount++;
        feedHeaderRow = rowCount++;
        feedSettingsRow = rowCount++;
        feedInfoRow = rowCount++;
        ghostHeaderRow = rowCount++;
        hideOnlineStatusRow = rowCount++;
        hideOnlineStatusInfoRow = rowCount++;
        keepLastSeenUpdatedInGhostModeRow = rowCount++;
        keepLastSeenUpdatedInGhostModeInfoRow = rowCount++;
        restrictionsHeaderRow = rowCount++;
        antiRecallRow = rowCount++;
        antiRecallInfoRow = rowCount++;
        saveTemporaryMediaRow = rowCount++;
        saveTemporaryMediaInfoRow = rowCount++;
        keepTemporaryMediaInChatRow = rowCount++;
        keepTemporaryMediaInChatInfoRow = rowCount++;
        keepKickedChatsCacheRow = rowCount++;
        keepKickedChatsCacheInfoRow = rowCount++;
        bypassContentProtectionRow = rowCount++;
        bypassContentProtectionInfoRow = rowCount++;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.CustomSettingsTitle));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        RecyclerListView listView = getRecyclerListView(context);

        root.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return fragmentView;
    }

    private void promptRestart() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.CustomSettingsRestartPromptTitle));
        builder.setMessage(getString(R.string.CustomSettingsRestartPromptMessage));
        builder.setPositiveButton(getString(R.string.CustomSettingsRestartNow), (dialog, which) -> {
            try {
                final PackageManager pm = activity.getPackageManager();
                final Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                if (intent != null) {
                    activity.startActivity(intent);
                }
            } catch (Exception ignore) {}
            System.exit(0);
        });
        builder.setNegativeButton(getString(R.string.CustomSettingsRestartLater), null);
        showDialog(builder.create());
    }

    @NonNull
    private RecyclerListView getRecyclerListView(Context context) {
        ListAdapter adapter = new ListAdapter(context);

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == hideAdsRow) {
                boolean val = !CustomSettings.hideAds();
                CustomSettings.setHideAds(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == hideProxySponsorRow) {
                boolean val = !CustomSettings.hideProxySponsor();
                CustomSettings.setHideProxySponsor(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == feedSettingsRow) {
                presentFragment(new FeedSettingsActivity());
            } else if (position == hideOnlineStatusRow) {
                boolean val = !CustomSettings.hideOnlineStatus();
                CustomSettings.setHideOnlineStatus(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == keepLastSeenUpdatedInGhostModeRow) {
                boolean val = !CustomSettings.keepLastSeenUpdatedInGhostMode();
                CustomSettings.setKeepLastSeenUpdatedInGhostMode(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == antiRecallRow) {
                boolean val = !CustomSettings.antiRecall();
                CustomSettings.setAntiRecall(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == saveTemporaryMediaRow) {
                boolean val = !CustomSettings.saveTemporaryMedia();
                CustomSettings.setSaveTemporaryMedia(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == keepTemporaryMediaInChatRow) {
                boolean val = !CustomSettings.keepTemporaryMediaInChat();
                CustomSettings.setKeepTemporaryMediaInChat(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == keepKickedChatsCacheRow) {
                boolean val = !CustomSettings.keepKickedChatsCache();
                CustomSettings.setKeepKickedChatsCache(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == bypassContentProtectionRow) {
                boolean val = !CustomSettings.bypassContentProtection();
                CustomSettings.setBypassContentProtection(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
                promptRestart();
            }
        });
        return listView;
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK  = 1;
    private static final int TYPE_INFO   = 2;
    private static final int TYPE_TEXT_CELL = 3;

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;

        ListAdapter(Context ctx) { this.ctx = ctx; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_CHECK || holder.getItemViewType() == TYPE_TEXT_CELL;
        }

        @Override
        public int getItemViewType(int pos) {
            if (pos == adsHeaderRow || pos == proxyHeaderRow)         return TYPE_HEADER;
            if (pos == hideAdsRow || pos == hideProxySponsorRow)      return TYPE_CHECK;
            if (pos == hideAdsInfoRow || pos == hideProxySponsorInfoRow) return TYPE_INFO;
            if (pos == feedHeaderRow)    return TYPE_HEADER;
            if (pos == feedSettingsRow)  return TYPE_TEXT_CELL;
            if (pos == feedInfoRow)      return TYPE_INFO;
            if (pos == ghostHeaderRow) return TYPE_HEADER;
            if (pos == hideOnlineStatusRow) return TYPE_CHECK;
            if (pos == hideOnlineStatusInfoRow) return TYPE_INFO;
            if (pos == keepLastSeenUpdatedInGhostModeRow) return TYPE_CHECK;
            if (pos == keepLastSeenUpdatedInGhostModeInfoRow) return TYPE_INFO;
            if (pos == restrictionsHeaderRow) return TYPE_HEADER;
            if (pos == antiRecallRow) return TYPE_CHECK;
            if (pos == antiRecallInfoRow) return TYPE_INFO;
            if (pos == saveTemporaryMediaRow) return TYPE_CHECK;
            if (pos == saveTemporaryMediaInfoRow) return TYPE_INFO;
            if (pos == keepTemporaryMediaInChatRow) return TYPE_CHECK;
            if (pos == keepTemporaryMediaInChatInfoRow) return TYPE_INFO;
            if (pos == keepKickedChatsCacheRow) return TYPE_CHECK;
            if (pos == keepKickedChatsCacheInfoRow) return TYPE_INFO;
            if (pos == bypassContentProtectionRow) return TYPE_CHECK;
            if (pos == bypassContentProtectionInfoRow) return TYPE_INFO;
            return TYPE_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_TEXT_CELL:
                    view = new TextCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (pos == adsHeaderRow)   cell.setText(getString(R.string.CustomSettingsAdvertisingHeader));
                    if (pos == proxyHeaderRow)  cell.setText(getString(R.string.Proxy));
                    if (pos == feedHeaderRow)   cell.setText(getString(R.string.CustomSettingsFeedHeader));
                    if (pos == ghostHeaderRow) cell.setText(getString(R.string.CustomSettingsGhostHeader));
                    if (pos == restrictionsHeaderRow) cell.setText(getString(R.string.CustomSettingsRestrictionsHeader));
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (pos == hideAdsRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideSponsoredMessages),
                                CustomSettings.hideAds(), true);
                    }
                    if (pos == hideProxySponsorRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideProxySponsor),
                                CustomSettings.hideProxySponsor(), false);
                    }
                    if (pos == hideOnlineStatusRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideOnlineStatus),
                                CustomSettings.hideOnlineStatus(), true);
                    }
                    if (pos == keepLastSeenUpdatedInGhostModeRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepLastSeenUpdatedInGhostMode),
                                CustomSettings.keepLastSeenUpdatedInGhostMode(), true);
                    }
                    if (pos == antiRecallRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsAntiRecall),
                                CustomSettings.antiRecall(), true);
                    }
                    if (pos == saveTemporaryMediaRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsSaveTemporaryMedia),
                                CustomSettings.saveTemporaryMedia(), true);
                    }
                    if (pos == keepTemporaryMediaInChatRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepTemporaryMediaInChat),
                                CustomSettings.keepTemporaryMediaInChat(), true);
                    }
                    if (pos == keepKickedChatsCacheRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepKickedChatsCache),
                                CustomSettings.keepKickedChatsCache(), true);
                    }
                    if (pos == bypassContentProtectionRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsBypassContentProtection),
                                CustomSettings.bypassContentProtection(), false);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (pos == hideAdsInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideAdsInfo));
                    }
                    if (pos == hideProxySponsorInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideProxySponsorInfo));
                    }
                    if (pos == feedInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsFeedInfo));
                    }
                    if (pos == hideOnlineStatusInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideOnlineStatusInfo));
                    }
                    if (pos == keepLastSeenUpdatedInGhostModeInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepLastSeenUpdatedInGhostModeInfo));
                    }
                    if (pos == antiRecallInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsAntiRecallInfo));
                    }
                    if (pos == saveTemporaryMediaInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsSaveTemporaryMediaInfo));
                    }
                    if (pos == keepTemporaryMediaInChatInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepTemporaryMediaInChatInfo));
                    }
                    if (pos == keepKickedChatsCacheInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepKickedChatsCacheInfo));
                    }
                    if (pos == bypassContentProtectionInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsBypassContentProtectionInfo));
                    }
                    break;
                }
                case TYPE_TEXT_CELL: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (pos == feedSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsFeedSettings), R.drawable.msg_channel, true);
                    }
                    break;
                }
            }
        }
    }
}
