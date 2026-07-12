package org.telegram.ui.Custom;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocalMessageFilterEngine;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class LocalMessageFiltersActivity extends BaseFragment {

    private static final int MENU_SAVE = 1;
    private EditText editor;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.LocalMessageFiltersTitle));
        actionBar.createMenu().addItemWithWidth(MENU_SAVE, R.drawable.ic_ab_done,
                AndroidUtilities.dp(56), LocaleController.getString(R.string.Save));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SAVE) {
                    save();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        TextView help = new TextView(context);
        help.setText(LocaleController.getString(R.string.LocalMessageFiltersHelp));
        help.setTextSize(14);
        help.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        help.setLineSpacing(AndroidUtilities.dp(2), 1f);
        content.addView(help, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, 12));

        TextView example = new TextView(context);
        example.setText(LocaleController.getString(R.string.LocalMessageFiltersInsertExample));
        example.setTextSize(15);
        example.setGravity(Gravity.CENTER);
        example.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        example.setBackground(Theme.getSelectorDrawable(false));
        example.setOnClickListener(view -> editor.setText(exampleJson()));
        content.addView(example, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        editor = new EditText(context);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setGravity(Gravity.TOP | Gravity.LEFT);
        editor.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editor.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editor.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(false);
        editor.setMinLines(18);
        editor.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        editor.setText(LocalMessageFilterEngine.getRulesJsonPretty());
        content.addView(editor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scrollView;
        return fragmentView;
    }

    private void save() {
        try {
            LocalMessageFilterEngine.setRulesJson(editor.getText().toString());
            finishFragment();
        } catch (JSONException | RuntimeException e) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.LocalMessageFiltersInvalid))
                    .setMessage(e.getMessage())
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
        }
    }

    private static String exampleJson() {
        return "[\n" +
                "  {\n" +
                "    \"name\": \"Collapse forwarded photos\",\n" +
                "    \"enabled\": true,\n" +
                "    \"priority\": 10,\n" +
                "    \"action\": \"collapse\",\n" +
                "    \"conditions\": {\n" +
                "      \"chatIds\": [-1001234567890],\n" +
                "      \"messageTypes\": [\"media\"],\n" +
                "      \"mediaTypes\": [\"photo\"],\n" +
                "      \"forwarded\": true\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"name\": \"Hide spam\",\n" +
                "    \"action\": \"hide\",\n" +
                "    \"conditions\": {\n" +
                "      \"senderIds\": [123456789],\n" +
                "      \"text\": {\"contains\": [\"promo\"], \"regex\": [\"https?://\"], \"caseSensitive\": false}\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"name\": \"Collapse join events\",\n" +
                "    \"action\": \"collapse\",\n" +
                "    \"conditions\": {\"serviceEvents\": [\"chat_add_user\", \"chat_joined_by_link\"]}\n" +
                "  }\n" +
                "]";
    }
}
