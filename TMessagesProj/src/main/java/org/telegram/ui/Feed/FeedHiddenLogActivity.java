package org.telegram.ui.Feed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Custom.CustomSettings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FeedHiddenLogActivity extends BaseFragment {

    private JSONArray logArray;
    private LogAdapter adapter;

    @Override
    public boolean onFragmentCreate() {
        logArray = CustomSettings.getHiddenLog();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Hidden Posts Log");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        actionBar.createMenu().addItem(1, R.drawable.msg_clear).setOnClickListener(v -> {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle("Clear Log")
                    .setMessage("Are you sure you want to clear the hidden posts log?")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        CustomSettings.clearHiddenLog();
                        logArray = new JSONArray();
                        adapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new LogAdapter(context);
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    private class LogAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        LogAdapter(Context ctx) { this.ctx = ctx; }

        @Override public int getItemCount() { return logArray.length() + 1; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return false; }

        @Override
        public int getItemViewType(int position) {
            return position == logArray.length() ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new TextView(ctx);
                ((TextView) view).setTextSize(15);
                ((TextView) view).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                ((TextView) view).setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(ctx);
                ((TextInfoPrivacyCell) view).setText("Posts hidden because of your word filter. They are already marked as read in channels.");
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < logArray.length()) {
                try {
                    JSONObject obj = logArray.getJSONObject(position);
                    long channelId = obj.optLong("channelId", 0);
                    String snippet = obj.optString("snippet", "");
                    long date = obj.optLong("date", 0);

                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-channelId);
                    String name = chat != null ? chat.title : "Channel " + channelId;

                    @SuppressLint("SimpleDateFormat")
                    String dateStr = new SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(new Date(date * 1000L));

                    ((TextView) holder.itemView).setText("📌 " + name + " · " + dateStr + "\n" + snippet);
                } catch (Exception e) {
                    ((TextView) holder.itemView).setText("Error reading entry");
                }
            }
        }
    }
}