package dev.acme.rag.verify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic post-check of a RAG answer against the chunks it was given.
 *  1. Citations: there is one, and every cited file was retrieved; no answer from empty context.
 *  2. Literals: every number, LaTeX formula and image path in the answer appears verbatim in the context
 *     (or the question). Models paraphrase prose fine but corrupt numbers, formulas and file names; this catches that.
 *  ponytail: string-level only. A wrong claim with correct literals passes; a formula the model rewrote
 *  equivalently fails (check those with eval/latex_equiv.py or an LLM judge). */
public final class AnswerCheck {
    public record Result(boolean grounded, boolean abstained, List<String> cited, List<String> issues) {}

    private static final Pattern BRACKETS = Pattern.compile("\\[([^\\[\\]]+)](?!\\()");
    private static final Pattern FILE = Pattern.compile("[^\\s/]+\\.\\w{1,5}");
    private static final Pattern LATEX = Pattern.compile("\\$\\$(.+?)\\$\\$|\\$(.+?)\\$|\\\\\\((.+?)\\\\\\)|\\\\\\[(.+?)\\\\]", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("[\\w./-]+\\.(?:png|jpe?g|gif|svg|webp)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    // ponytail: phrase list tuned to small local models; extend when the eval shows missed refusals
    private static final Pattern REFUSAL = Pattern.compile("(?i)\\b(i (do not|don't) know"
            + "|(cannot|can ?not|can't|unable to) (answer|find|determine)"
            + "|(does not|doesn't|do not|don't) (mention|contain|say|provide|specify|cover)"
            + "|no information)");

    private AnswerCheck() {}

    /** Citation-only check, for callers that do not have the chunk text. */
    public static Result check(String answer, List<String> retrievedSources) {
        return check("", answer, retrievedSources, null);
    }

    /** @param context the retrieved chunk texts joined; null skips the literal check */
    public static Result check(String question, String answer, List<String> retrievedSources, String context) {
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
            if (context != null) literals(question, answer, context, issues);
        }
        return new Result(issues.isEmpty(), abstained, List.copyOf(cited), issues);
    }

    private static void literals(String question, String answer, String context, List<String> issues) {
        String source = question + "\n" + context;
        String flatSource = squash(source);
        String rest = BRACKETS.matcher(answer).replaceAll(" "); // citations are checked above

        var f = LATEX.matcher(rest);
        while (f.find()) {
            String tex = firstGroup(f);
            if (!flatSource.contains(squash(tex))) issues.add("formula not in context: " + tex.strip());
        }
        rest = LATEX.matcher(rest).replaceAll(" "); // numbers inside a verified formula are already covered

        var img = IMAGE.matcher(rest);
        while (img.find()) if (!source.contains(img.group())) issues.add("image not in context: " + img.group());
        rest = IMAGE.matcher(rest).replaceAll(" ");

        Set<String> known = numbers(source);
        for (String n : numbers(rest)) if (!known.contains(n)) issues.add("number not in context: " + n);
    }

    /** "4,000" and "4000" are the same number; "99.9%" -> "99.9". ponytail: English separators only. */
    private static Set<String> numbers(String s) {
        var out = new LinkedHashSet<String>();
        var m = NUMBER.matcher(s);
        while (m.find()) out.add(m.group().replace(",", ""));
        return out;
    }

    private static String squash(String s) { return s.replaceAll("\\s+", ""); }

    private static String firstGroup(java.util.regex.Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return m.group(i);
        return m.group();
    }
}
