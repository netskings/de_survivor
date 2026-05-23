package org.telegram.ui.Feed;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.List;

public class FeedBanListActivity extends BaseFragment {

    private List<CustomSettings.BanGroup> groups = new ArrayList<>();
    private ListAdapter adapter;

    @Override
    public boolean onFragmentCreate() {
        groups = CustomSettings.getBanGroups();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.FeedBannedPhrases));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new ListAdapter(context);
        RecyclerListView listView = getRecyclerListView(context);

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    @NonNull
    private RecyclerListView getRecyclerListView(Context context) {
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == groups.size()) {
                showAddGroupDialog();
            } else {
                presentFragment(new FeedBanGroupDetailActivity(groups.get(position).id));
            }
        });
        return listView;
    }

    @Override
    public void onResume() {
        super.onResume();
        groups = CustomSettings.getBanGroups();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showAddGroupDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.FeedBanNewGroup));

        EditTextCell inputCell = new EditTextCell(getParentActivity(),
                LocaleController.getString(R.string.FeedBanGroupNameHint), false);
        inputCell.hideKeyboardOnEnter();

        builder.setView(inputCell);
        builder.setPositiveButton(LocaleController.getString(R.string.Create), (dialog, which) -> {
            String name = inputCell.getText().toString().trim();
            if (!name.isEmpty()) {
                groups.add(new CustomSettings.BanGroup(null, name, true, new ArrayList<>()));
                CustomSettings.saveBanGroups(groups);
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        ListAdapter(Context ctx) { this.ctx = ctx; }

        @Override public int getItemCount() { return groups.size() + 2; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }

        @Override
        public int getItemViewType(int position) {
            if (position == groups.size()) return 1;
            if (position == groups.size() + 1) return 2;
            return 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0 || viewType == 1) {
                view = new TextCell(ctx);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(ctx);
                view.setBackground(Theme.getThemedDrawableByKey(ctx, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == groups.size()) {
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndIcon(LocaleController.getString(R.string.FeedBanCreateNewGroup),
                        R.drawable.msg_add, false);
            } else if (position == groups.size() + 1) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(R.string.FeedBanListInfo));
            } else {
                TextCell cell = (TextCell) holder.itemView;
                CustomSettings.BanGroup g = groups.get(position);
                String status = g.enabled
                        ? LocaleController.getString(R.string.FeedBanStatusOn)
                        : LocaleController.getString(R.string.FeedBanStatusOff);
                String phrases = g.phrases.isEmpty()
                        ? LocaleController.getString(R.string.FeedBanNoPhrases)
                        : LocaleController.formatPluralString("FeedBanPhrases",
                                g.phrases.size());
                cell.setTextAndValue(g.name + " (" + status + ")", phrases, false);
            }
        }
    }
}
