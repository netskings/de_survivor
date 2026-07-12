package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Local-only message presentation rules. This class never changes a message, the local database,
 * or server state; callers use the returned decision only while binding UI rows.
 */
public final class LocalMessageFilterEngine {

    public enum Action {
        SHOW,
        COLLAPSE,
        HIDE
    }

    public static final class Decision {
        public static final Decision SHOW = new Decision(Action.SHOW, null);

        public final Action action;
        public final String ruleName;

        public Decision(Action action, String ruleName) {
            this.action = action == null ? Action.SHOW : action;
            this.ruleName = ruleName;
        }
    }

    /** Pure value object used by the matcher and unit tests. */
    public static final class Facts {
        public long senderId;
        public long chatId;
        public String messageType;
        public String text;
        public String mediaType;
        public boolean forwarded;
        public String serviceEvent;
    }

    public static final class CompiledRules {
        private final List<Rule> rules;

        private CompiledRules(List<Rule> rules) {
            this.rules = rules;
        }

        public Decision evaluate(Facts facts) {
            if (facts == null) {
                return Decision.SHOW;
            }
            for (Rule rule : rules) {
                if (rule.matches(facts)) {
                    return new Decision(rule.action, rule.name);
                }
            }
            return Decision.SHOW;
        }

        public int size() {
            return rules.size();
        }
    }

    private static final String PREFS_NAME = "local_message_filters";
    private static final String KEY_RULES = "rules_json";
    private static final String EMPTY_RULES = "[]";

    private static String cachedJson;
    private static CompiledRules cachedRules = new CompiledRules(Collections.emptyList());

    private LocalMessageFilterEngine() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    public static String getRulesJson() {
        return preferences().getString(KEY_RULES, EMPTY_RULES);
    }

    public static String getRulesJsonPretty() {
        try {
            return new JSONArray(getRulesJson()).toString(2);
        } catch (JSONException | RuntimeException e) {
            return EMPTY_RULES;
        }
    }

    public static void setRulesJson(String json) throws JSONException {
        CompiledRules compiled = compile(json);
        String normalized = new JSONArray(json).toString();
        synchronized (LocalMessageFilterEngine.class) {
            cachedJson = normalized;
            cachedRules = compiled;
        }
        preferences().edit().putString(KEY_RULES, normalized).apply();
    }

    public static String getRevision() {
        return getRulesJson();
    }

    public static Decision evaluate(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isDateObject || messageObject.getId() == 0) {
            return Decision.SHOW;
        }
        return getCompiledRules().evaluate(toFacts(messageObject));
    }

    public static CompiledRules compile(String json) throws JSONException {
        JSONArray array = new JSONArray(json == null || json.isEmpty() ? EMPTY_RULES : json);
        ArrayList<Rule> rules = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            Rule rule = Rule.fromJson(object, i);
            if (rule.enabled) {
                rules.add(rule);
            }
        }
        Collections.sort(rules, Comparator.comparingInt(rule -> rule.priority));
        return new CompiledRules(Collections.unmodifiableList(rules));
    }

    private static CompiledRules getCompiledRules() {
        String json = getRulesJson();
        synchronized (LocalMessageFilterEngine.class) {
            if (json.equals(cachedJson)) {
                return cachedRules;
            }
            try {
                cachedRules = compile(json);
            } catch (JSONException | RuntimeException e) {
                FileLog.e(e);
                cachedRules = new CompiledRules(Collections.emptyList());
            }
            cachedJson = json;
            return cachedRules;
        }
    }

    private static Facts toFacts(MessageObject object) {
        Facts facts = new Facts();
        TLRPC.Message message = object.messageOwner;
        facts.senderId = message.from_id == null ? 0 : MessageObject.getPeerId(message.from_id);
        facts.chatId = object.getDialogId();
        facts.text = collectText(object);
        facts.forwarded = message.fwd_from != null;
        facts.serviceEvent = serviceEvent(message.action);
        facts.mediaType = mediaType(object);
        if (!TextUtils.isEmpty(facts.serviceEvent)) {
            facts.messageType = "service";
        } else if (!"none".equals(facts.mediaType)) {
            facts.messageType = "media";
        } else {
            facts.messageType = "text";
        }
        return facts;
    }

    private static String collectText(MessageObject object) {
        StringBuilder result = new StringBuilder();
        if (object.messageOwner.message != null) {
            result.append(object.messageOwner.message);
        }
        if (object.caption != null && object.caption.length() > 0) {
            String caption = object.caption.toString();
            if (!caption.contentEquals(result)) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(caption);
            }
        }
        return result.toString();
    }

    private static String mediaType(MessageObject object) {
        if (object.isPhoto()) return "photo";
        if (object.isRoundVideo()) return "round_video";
        if (object.isVideo()) return "video";
        if (object.isGif()) return "gif";
        if (object.isVoice()) return "voice";
        if (object.isMusic()) return "music";
        if (object.isAnyKindOfSticker()) return "sticker";
        if (object.isPoll()) return "poll";
        if (object.isLocation()) return "location";
        if (object.isGame()) return "game";
        if (object.isInvoice()) return "invoice";
        if (object.isStory()) return "story";
        TLRPC.MessageMedia media = MessageObject.getMedia(object.messageOwner);
        if (media instanceof TLRPC.TL_messageMediaContact) return "contact";
        if (media instanceof TLRPC.TL_messageMediaDice) return "dice";
        if (media instanceof TLRPC.TL_messageMediaWebPage) return "webpage";
        if (object.isDocument()) return "document";
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) return "none";
        return "other";
    }

    private static String serviceEvent(TLRPC.MessageAction action) {
        if (action == null || action instanceof TLRPC.TL_messageActionEmpty) {
            return "";
        }
        String value = action.getClass().getSimpleName();
        if (value.startsWith("TL_messageAction")) {
            value = value.substring("TL_messageAction".length());
        }
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && normalized.length() > 0) {
                normalized.append('_');
            }
            normalized.append(Character.toLowerCase(c));
        }
        return normalized.toString().replaceFirst("_layer\\d+$", "");
    }

    private static final class Rule {
        String name;
        boolean enabled;
        int priority;
        Action action;
        Set<Long> senderIds;
        Set<Long> chatIds;
        Set<String> messageTypes;
        Set<String> mediaTypes;
        Set<String> serviceEvents;
        Boolean forwarded;
        List<String> contains;
        List<Pattern> regex;
        boolean caseSensitive;

        static Rule fromJson(JSONObject object, int position) throws JSONException {
            Rule rule = new Rule();
            rule.name = object.optString("name", "Rule " + (position + 1));
            rule.enabled = object.optBoolean("enabled", true);
            rule.priority = object.optInt("priority", position);
            String action = object.optString("action", "hide").toUpperCase(Locale.ROOT);
            try {
                rule.action = Action.valueOf(action);
            } catch (IllegalArgumentException e) {
                throw new JSONException("Unknown action in '" + rule.name + "': " + action);
            }
            JSONObject conditions = object.optJSONObject("conditions");
            if (conditions == null) {
                throw new JSONException("Rule '" + rule.name + "' must contain conditions");
            }
            rule.senderIds = longSet(conditions.optJSONArray("senderIds"));
            rule.chatIds = longSet(conditions.optJSONArray("chatIds"));
            rule.messageTypes = stringSet(conditions.optJSONArray("messageTypes"));
            rule.mediaTypes = stringSet(conditions.optJSONArray("mediaTypes"));
            rule.serviceEvents = stringSet(conditions.optJSONArray("serviceEvents"));
            if (conditions.has("forwarded")) {
                rule.forwarded = conditions.getBoolean("forwarded");
            }
            JSONObject text = conditions.optJSONObject("text");
            if (text != null) {
                rule.caseSensitive = text.optBoolean("caseSensitive", false);
                rule.contains = stringList(text.optJSONArray("contains"), rule.caseSensitive);
                List<String> regexStrings = stringList(text.optJSONArray("regex"), true);
                if (!regexStrings.isEmpty()) {
                    rule.regex = new ArrayList<>();
                    int flags = rule.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    for (String expression : regexStrings) {
                        try {
                            rule.regex.add(Pattern.compile(expression, flags));
                        } catch (PatternSyntaxException e) {
                            throw new JSONException("Invalid regex in '" + rule.name + "': " + e.getMessage());
                        }
                    }
                }
            }
            if (!rule.hasConditions()) {
                throw new JSONException("Rule '" + rule.name + "' has no supported conditions");
            }
            return rule;
        }

        boolean hasConditions() {
            return !senderIds.isEmpty() || !chatIds.isEmpty() || !messageTypes.isEmpty() ||
                    !mediaTypes.isEmpty() || !serviceEvents.isEmpty() || forwarded != null ||
                    contains != null && !contains.isEmpty() || regex != null && !regex.isEmpty();
        }

        boolean matches(Facts facts) {
            if (!senderIds.isEmpty() && !senderIds.contains(facts.senderId)) return false;
            if (!chatIds.isEmpty() && !chatIds.contains(facts.chatId)) return false;
            if (!messageTypes.isEmpty() && !messageTypes.contains(normalize(facts.messageType))) return false;
            if (!mediaTypes.isEmpty() && !mediaTypes.contains(normalize(facts.mediaType))) return false;
            if (!serviceEvents.isEmpty() && !serviceEvents.contains(normalize(facts.serviceEvent))) return false;
            if (forwarded != null && forwarded != facts.forwarded) return false;
            String sourceText = facts.text == null ? "" : facts.text;
            String comparedText = caseSensitive ? sourceText : sourceText.toLowerCase(Locale.ROOT);
            if (contains != null && !contains.isEmpty()) {
                boolean found = false;
                for (String needle : contains) {
                    if (comparedText.contains(needle)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            if (regex != null && !regex.isEmpty()) {
                boolean found = false;
                for (Pattern pattern : regex) {
                    if (pattern.matcher(sourceText).find()) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }

        private static Set<Long> longSet(JSONArray array) throws JSONException {
            if (array == null) return Collections.emptySet();
            Set<Long> result = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (value instanceof Number) {
                    result.add(((Number) value).longValue());
                } else {
                    try {
                        result.add(Long.parseLong(String.valueOf(value)));
                    } catch (NumberFormatException e) {
                        throw new JSONException("Expected numeric id, got: " + value);
                    }
                }
            }
            return result;
        }

        private static Set<String> stringSet(JSONArray array) throws JSONException {
            return new HashSet<>(stringList(array, false));
        }

        private static List<String> stringList(JSONArray array, boolean keepCase) throws JSONException {
            if (array == null) return Collections.emptyList();
            ArrayList<String> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String value = array.getString(i);
                result.add(keepCase ? value : normalize(value));
            }
            return result;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
