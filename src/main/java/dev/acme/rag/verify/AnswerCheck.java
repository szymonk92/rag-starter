package dev.acme.rag.verify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic post-check of a RAG answer against the chunks it was given. Catches the cheap, common failures:
 *  no citation, a citation to a file that was never retrieved, and an answer produced with empty context.
 *  ponytail: citation-level only; it cannot tell a correct claim from a wrong one in a correctly cited file.
 *  For that, add an LLM-judge / NLI groundedness check (e.g. Spring AI FactCheckingEvaluator) on top. */
public final class AnswerCheck {
    public record Result(boolean grounded, boolean abstained, List<String> cited, List<String> issues) {}

    private static final Pattern BRACKETS = Pattern.compile("\\[([^\\[\\]]+)]");
    private static final Pattern FILE = Pattern.compile("[^\\s/]+\\.\\w{1,5}");
    // ponytail: phrase list tuned to small local models; extend when the eval shows missed refusals
    private static final Pattern REFUSAL = Pattern.compile("(?i)\\b(i (do not|don't) know"
            + "|(cannot|can ?not|can't|unable to) (answer|find|determine)"
            + "|(does not|doesn't|do not|don't) (mention|contain|say|provide|specify|cover)"
            + "|no information)");

    private AnswerCheck() {}

    public static Result check(String answer, List<String> retrievedSources) {
        var retrieved = retrievedSources.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        boolean abstained = REFUSAL.matcher(answer).find();
        var cited = new LinkedHashSet<String>();
        var m = BRACKETS.matcher(answer);
        while (m.find())
            for (String part : m.group(1).split("[,;]")) {
                String p = part.strip();
                p = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (FILE.matcher(p).matches()) cited.add(p);
            }

        var issues = new ArrayList<String>();
        if (!abstained) {
            if (retrieved.isEmpty()) issues.add("answered with no retrieved context");
            if (cited.isEmpty()) issues.add("no citation");
            for (String c : cited) if (!retrieved.contains(c)) issues.add("cited " + c + ", which was not retrieved");
        }
        return new Result(issues.isEmpty(), abstained, List.copyOf(cited), issues);
    }
}
