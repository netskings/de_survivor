package org.telegram.ui.Custom;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
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
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Custom Settings");
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
                case TYPE_TEXT_CELL:
                    view = new TextCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
                    if (pos == adsHeaderRow)   cell.setText("Advertising");
                    if (pos == proxyHeaderRow)  cell.setText("Proxy");
                    if (pos == feedHeaderRow)   cell.setText("Feed");
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (pos == hideAdsRow) {
                        cell.setTextAndCheck("Hide Sponsored Messages",
                                CustomSettings.hideAds(), true);
                    }
                    if (pos == hideProxySponsorRow) {
                        cell.setTextAndCheck("Hide Proxy Sponsor",
                                CustomSettings.hideProxySponsor(), false);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (pos == hideAdsInfoRow) {
                        cell.setText("Completely hides sponsored messages " +
                                "in channels without Telegram Premium");
                    }
                    if (pos == hideProxySponsorInfoRow) {
                        cell.setText("Hides the proxy sponsor channel " +
                                "from your chat list and feed");
                    }
                    if (pos == feedInfoRow) {
                        cell.setText("Manage your feed channels and recommendations");
                    }
                    break;
                }
                case TYPE_TEXT_CELL: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (pos == feedSettingsRow) {
                        cell.setTextAndIcon("Feed Settings", R.drawable.msg_channel, true);
                    }
                    break;
                }
            }
        }
    }
}