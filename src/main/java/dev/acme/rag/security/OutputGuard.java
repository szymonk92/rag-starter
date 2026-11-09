package dev.acme.rag.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last line of defence on model output: removes markdown images, links and e-mail addresses to hosts outside an allowlist.
 *  Deterministic, so it still works when the model has been talked into something by an injected document. */
public final class OutputGuard {
    public record Result(String text, List<String> removed) {}

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((https?://([^/)\\s]+)[^)]*)\\)");
    private static final Pattern LINK = Pattern.compile("https?://([^/\\s)]+)\\S*");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@([\\w-]+\\.[\\w.-]+)");
    private final Set<String> allowedHosts;

    public OutputGuard(Set<String> allowedHosts) { this.allowedHosts = allowedHosts; }

    public Result apply(String text) {
        var removed = new ArrayList<String>();
        text = replace(IMAGE, 2, text, removed); // images first: a markdown image is fetched silently by the browser (exfiltration)
        text = replace(LINK, 1, text, removed);
        text = replace(EMAIL, 1, text, removed);
        return new Result(text, removed);
    }

    private String replace(Pattern p, int hostGroup, String text, List<String> removed) {
        Matcher m = p.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            String host = m.group(hostGroup).toLowerCase().split(":")[0];
            boolean ok = allowedHosts.contains(host);
            if (!ok) removed.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(ok ? m.group() : "[removed]"));
        }
        return m.appendTail(sb).toString();
    }
}
