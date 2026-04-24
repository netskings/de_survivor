package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Custom.CustomSettings;

import java.util.List;

public class FeedBanGroupDetailActivity extends BaseFragment {

    private final String groupId;
    private CustomSettings.BanGroup group;
    private ListAdapter adapter;

    public FeedBanGroupDetailActivity(String groupId) {
        this.groupId = groupId;
    }

    @Override
    public boolean onFragmentCreate() {
        for (CustomSettings.BanGroup g : CustomSettings.getBanGroups()) {
            if (g.id.equals(groupId)) {
                group = g;
                break;
            }
        }
        if (group == null) return false;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(group.name);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        actionBar.createMenu().addItem(1, R.drawable.msg_delete).setOnClickListener(v -> {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle("Delete Group")
                    .setMessage("Are you sure you want to delete \"" + group.name + "\"?")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        List<CustomSettings.BanGroup> allGroups = CustomSettings.getBanGroups();
                        allGroups.removeIf(g -> g.id.equals(group.id));
                        CustomSettings.saveBanGroups(allGroups);
                        finishFragment();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new ListAdapter(context);
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == 0) {
                group.enabled = !group.enabled;
                saveAndRefresh();
            } else if (position == group.phrases.size() + 1) {
                showAddPhraseDialog();
            }
        });

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    private void showAddPhraseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Add Phrase");

        EditTextCell inputCell = new EditTextCell(getParentActivity(), "Word or phrase (e.g., #ad)", false);
        inputCell.hideKeyboardOnEnter();

        builder.setView(inputCell);
        builder.setPositiveButton("Add", (dialog, which) -> {
            String phrase = inputCell.getText().toString().trim();
            if (!phrase.isEmpty() && !group.phrases.contains(phrase)) {
                group.phrases.add(phrase);
                saveAndRefresh();
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void removePhrase(int index) {
        group.phrases.remove(index);
        saveAndRefresh();
    }

    private void saveAndRefresh() {
        List<CustomSettings.BanGroup> allGroups = CustomSettings.getBanGroups();
        for (int i = 0; i < allGroups.size(); i++) {
            if (allGroups.get(i).id.equals(group.id)) {
                allGroups.set(i, group);
                break;
            }
        }
        CustomSettings.saveBanGroups(allGroups);
        adapter.notifyDataSetChanged();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        ListAdapter(Context ctx) { this.ctx = ctx; }

        @Override
        public int getItemCount() {
            return 1 + group.phrases.size() + 2;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 0 || holder.getItemViewType() == 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return 0;
            if (position == group.phrases.size() + 1) return 2;
            if (position == group.phrases.size() + 2) return 3;
            return 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextCheckCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new PhraseCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 0) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Group Enabled", group.enabled, true);
            } else if (type == 1) {
                PhraseCell cell = (PhraseCell) holder.itemView;
                cell.setPhrase(group.phrases.get(position - 1), position - 1);
            } else if (type == 2) {
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndIcon("Add Phrase", R.drawable.msg_add, false);
            } else if (type == 3) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText("Posts containing any of these phrases will be hidden. Tap a phrase to remove it.");
            }
        }
    }

    private class PhraseCell extends FrameLayout {
        private final TextView textView;
        private final ImageView deleteButton;
        private int currentIndex = -1;

        public PhraseCell(Context context) {
            super(context);
            textView = new TextView(context);
            textView.setTextSize(16);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 0, 56, 0));

            deleteButton = new ImageView(context);
            deleteButton.setScaleType(ImageView.ScaleType.CENTER);
            deleteButton.setImageResource(R.drawable.msg_close);
            deleteButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            deleteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            deleteButton.setOnClickListener(v -> {
                if (currentIndex >= 0) removePhrase(currentIndex);
            });
            addView(deleteButton, LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 4, 0));

            View divider = new View(context) {
                private final Paint paint = new Paint();
                { paint.setColor(Theme.getColor(Theme.key_divider)); }
                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.drawRect(dp(16), 0, getWidth(), getHeight(), paint);
                }
            };
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));
        }

        public void setPhrase(String phrase, int index) {
            this.currentIndex = index;
            textView.setText(phrase);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY));
        }
    }
}