package org.telegram.messenger;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocalMessageFilterEngineTest {

    @Test
    public void matchesAllConditionFamilies() throws Exception {
        LocalMessageFilterEngine.CompiledRules rules = LocalMessageFilterEngine.compile("[" +
                "{\"name\":\"specific\",\"priority\":10,\"action\":\"collapse\",\"conditions\":{" +
                "\"senderIds\":[42],\"chatIds\":[-100],\"messageTypes\":[\"media\"]," +
                "\"mediaTypes\":[\"photo\"],\"forwarded\":true," +
                "\"text\":{\"contains\":[\"offer\"],\"regex\":[\"https?://\"]}}}" +
                "]");
        LocalMessageFilterEngine.Facts facts = facts();
        assertEquals(LocalMessageFilterEngine.Action.COLLAPSE, rules.evaluate(facts).action);
        facts.forwarded = false;
        assertEquals(LocalMessageFilterEngine.Action.SHOW, rules.evaluate(facts).action);
    }

    @Test
    public void lowerPriorityWinsAndDisabledRulesAreIgnored() throws Exception {
        LocalMessageFilterEngine.CompiledRules rules = LocalMessageFilterEngine.compile("[" +
                "{\"name\":\"disabled\",\"enabled\":false,\"priority\":0,\"action\":\"hide\",\"conditions\":{\"senderIds\":[42]}}," +
                "{\"name\":\"collapse\",\"priority\":20,\"action\":\"collapse\",\"conditions\":{\"senderIds\":[42]}}," +
                "{\"name\":\"hide\",\"priority\":30,\"action\":\"hide\",\"conditions\":{\"senderIds\":[42]}}" +
                "]");
        assertEquals(LocalMessageFilterEngine.Action.COLLAPSE, rules.evaluate(facts()).action);
    }

    @Test
    public void matchesServiceEvents() throws Exception {
        LocalMessageFilterEngine.CompiledRules rules = LocalMessageFilterEngine.compile("[" +
                "{\"action\":\"hide\",\"conditions\":{\"messageTypes\":[\"service\"],\"serviceEvents\":[\"chat_add_user\"]}}" +
                "]");
        LocalMessageFilterEngine.Facts facts = facts();
        facts.messageType = "service";
        facts.mediaType = "none";
        facts.serviceEvent = "chat_add_user";
        assertEquals(LocalMessageFilterEngine.Action.HIDE, rules.evaluate(facts).action);
    }

    @Test(expected = JSONException.class)
    public void rejectsMalformedRegex() throws Exception {
        LocalMessageFilterEngine.compile("[{\"action\":\"hide\",\"conditions\":{\"text\":{\"regex\":[\"(\"]}}}]");
    }

    @Test(expected = JSONException.class)
    public void rejectsRuleWithoutConditions() throws Exception {
        LocalMessageFilterEngine.compile("[{\"action\":\"hide\",\"conditions\":{}}]");
    }

    private static LocalMessageFilterEngine.Facts facts() {
        LocalMessageFilterEngine.Facts facts = new LocalMessageFilterEngine.Facts();
        facts.senderId = 42;
        facts.chatId = -100;
        facts.messageType = "media";
        facts.mediaType = "photo";
        facts.forwarded = true;
        facts.text = "Limited OFFER at https://example.test";
        facts.serviceEvent = "";
        return facts;
    }
}
