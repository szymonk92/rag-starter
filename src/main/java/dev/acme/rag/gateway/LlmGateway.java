package dev.acme.rag.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Per-tenant token budget -> exact cache -> semantic cache -> route small/large -> fallback chain. Same design as gateway.py. */
public class LlmGateway {

    public interface ModelClient { Reply chat(String model, String prompt) throws Exception; }
    public record Reply(String text, int tokens) {}
    public record Result(int status, String answer, String model, String route, List<String> tried, double sim, int tokens) {}

    /** Token bucket refilled continuously. ponytail: in-process; use Redis or the API gateway when you run several replicas. */
    public static final class TokenBudget {
        private final int perMinute; private final LongSupplier nanos; private double level; private long last;
        public TokenBudget(int perMinute, LongSupplier nanos) { this.perMinute = perMinute; this.nanos = nanos; this.level = perMinute; this.last = nanos.getAsLong(); }
        public TokenBudget(int perMinute) { this(perMinute, System::nanoTime); }
        public synchronized boolean take(int n) {
            long now = nanos.getAsLong();
            double elapsedS = Math.max(0, now - last) / 1e9; // never negative
            level = Math.min(perMinute, level + elapsedS * perMinute / 60.0); last = now;
            if (level < n) return false;
            level -= n; return true;
        }
    }

    static final Pattern NEVER_CACHE = Pattern.compile("\\b(now|today|yesterday|tomorrow|currently|current|latest|status|this week)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern HARD = Pattern.compile("\\b(why|compare|explain|design|trade-?offs?|step by step|analy[sz]e)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern UNSURE = Pattern.compile("\\b(i don't know|i'm not sure|cannot answer|unclear)\\b", Pattern.CASE_INSENSITIVE);

    /** Exact (normalised) match, then nearest stored question above the threshold; scoped per tenant; never caches time-sensitive questions. */
    public static final class SemanticCache {
        private final Function<String, float[]> embed; private final double threshold;
        private final Map<String, String> exact = new HashMap<>();
        private final Map<String, List<Map.Entry<float[], String>>> items = new HashMap<>();
        public SemanticCache(Function<String, float[]> embed, double threshold) { this.embed = embed; this.threshold = threshold; }
        static boolean cacheable(String q) { return !NEVER_CACHE.matcher(q).find(); }
        static String key(String tenant, String q) {
            try {
                var d = MessageDigest.getInstance("SHA-256").digest(String.join(" ", q.toLowerCase(Locale.ROOT).trim().split("\\s+")).getBytes(StandardCharsets.UTF_8));
                return tenant + ":" + HexFormat.of().formatHex(d);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        /** returns [answer, kind, similarity] or null answer on miss */
        synchronized Object[] get(String tenant, String q) {
            if (!cacheable(q)) return new Object[]{null, null, 0.0};
            String hit = exact.get(key(tenant, q));
            if (hit != null) return new Object[]{hit, "exact", 1.0};
            var list = items.get(tenant);
            if (list == null || list.isEmpty()) return new Object[]{null, null, 0.0};
            float[] v = embed.apply(q);
            Map.Entry<float[], String> best = null; double bestSim = -1;
            for (var e : list) { double s = cos(v, e.getKey()); if (s > bestSim) { bestSim = s; best = e; } } // ponytail: linear scan; vector index past ~10k
            return bestSim >= threshold ? new Object[]{best.getValue(), "semantic", bestSim} : new Object[]{null, null, bestSim};
        }
        synchronized void put(String tenant, String q, String answer) {
            if (!cacheable(q)) return;
            exact.put(key(tenant, q), answer);
            items.computeIfAbsent(tenant, t -> new ArrayList<>()).add(Map.entry(embed.apply(q), answer));
        }
    }

    static double cos(float[] a, float[] b) {
        double d = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return d / Math.sqrt(na * nb);
    }

    /** Cheap heuristic router. ponytail: swap for a trained classifier once you have labelled traffic. */
    static String route(String q) { return HARD.matcher(q).find() || q.split("\\s+").length > 25 ? "large" : "small"; }

    private final ModelClient client; private final Map<String, List<String>> models;
    private final SemanticCache cache; private final Map<String, TokenBudget> budgets; private final int estTokens;

    public LlmGateway(ModelClient client, Map<String, List<String>> models, SemanticCache cache, Map<String, TokenBudget> budgets, int estTokens) {
        this.client = client; this.models = models; this.cache = cache; this.budgets = budgets; this.estTokens = estTokens;
    }

    public Result ask(String tenant, String q) {
        Object[] c = cache.get(tenant, q); // cache first: hits cost no tokens, so they must not spend budget
        if (c[0] != null) return new Result(200, (String) c[0], "cache:" + c[1], null, List.of(), (double) c[2], 0);
        if (!budgets.get(tenant).take(estTokens)) return new Result(429, "Token budget exceeded, retry later.", null, null, List.of(), 0, 0);
        String tier = route(q);
        var chain = new LinkedHashSet<>(models.get(tier));
        if (tier.equals("small")) chain.addAll(models.get("large")); // escalate small -> large
        var tried = new ArrayList<String>();
        for (String name : chain) {
            tried.add(name);
            Reply r;
            try { r = client.chat(name, q); } catch (Exception e) { continue; } // provider down, model missing, timeout -> next
            if (tier.equals("small") && models.get("small").contains(name) && UNSURE.matcher(r.text()).find()) continue; // not confident: escalate
            cache.put(tenant, q, r.text());
            return new Result(200, r.text(), name, tier, List.copyOf(tried), 0, r.tokens());
        }
        return new Result(503, "All models failed.", null, tier, List.copyOf(tried), 0, 0);
    }
}
