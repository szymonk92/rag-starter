package dev.acme.rag.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class LlmGatewayTest {
    /** fake models: "broken" throws, "tiny" is unsure about refunds; fake 3-d topic embedding */
    static final LlmGateway.ModelClient FAKE = (model, prompt) -> {
        if (model.equals("broken")) throw new java.io.IOException("down");
        if (model.equals("tiny") && prompt.contains("refund")) return new LlmGateway.Reply("I don't know.", 10);
        return new LlmGateway.Reply(model + " answer", 50);
    };
    static float[] embed(String t) {
        t = t.toLowerCase(Locale.ROOT);
        return new float[]{(t.contains("password") ? 1 : 0) + .01f, (t.contains("refund") || t.contains("money") ? 1 : 0) + .01f, (t.contains("down") ? 1 : 0) + .01f};
    }
    LlmGateway gw() {
        return new LlmGateway(FAKE, Map.of("small", List.of("broken", "tiny"), "large", List.of("big")),
                new LlmGateway.SemanticCache(LlmGatewayTest::embed, 0.9),
                Map.of("a", new LlmGateway.TokenBudget(10_000), "b", new LlmGateway.TokenBudget(500)), 400);
    }

    @Test void fallbackCacheEscalationRouting() {
        var gw = gw();
        var r = gw.ask("a", "How do I reset my password?");
        assertEquals("tiny", r.model()); assertEquals(List.of("broken", "tiny"), r.tried());           // fallback past a failing model
        assertEquals("cache:exact", gw.ask("a", "how do i reset my   password?").model());             // normalised exact hit
        assertEquals("cache:semantic", gw.ask("a", "password reset steps").model());                   // paraphrase hit
        r = gw.ask("a", "What is the refund policy?");
        assertEquals("big", r.model()); assertEquals("small", r.route());                              // unsure small model escalates
        assertEquals("large", gw.ask("a", "Explain the trade-offs of microservices").route());         // router
    }

    @Test void neverCachesTimeSensitiveOrAcrossTenants() {
        var gw = gw();
        gw.ask("a", "Is the service down now?");
        assertNotEquals("cache:exact", gw.ask("a", "Is the service down now?").model());
        gw.ask("a", "How do I reset my password?");
        assertNotEquals("cache:exact", gw.ask("b", "How do I reset my password?").model());
    }

    @Test void budgetAndRefill() {
        var gw = gw();
        assertEquals(200, gw.ask("b", "How do I reset my password?").status()); // distinct fake vectors, so q2 below is a miss
        assertEquals(429, gw.ask("b", "q2").status());                                                 // 500 < 2 x 400
        var clock = new AtomicLong();
        var b = new LlmGateway.TokenBudget(60, clock::get);
        assertTrue(b.take(60)); assertFalse(b.take(1));
        clock.set(30_000_000_000L); assertTrue(b.take(30)); assertFalse(b.take(1));                     // 1 token per second
    }
}
