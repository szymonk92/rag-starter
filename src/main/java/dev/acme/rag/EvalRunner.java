package dev.acme.rag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** mvn spring-boot:run -Dspring-boot.run.profiles=eval : ingest, then score retrieval and answers on eval.tsv. */
@Component
@Profile("eval")
public class EvalRunner implements CommandLineRunner {
    private final Ingestor ingestor;
    private final VectorStore store;
    private final AskController ask;
    private final int topK;

    public EvalRunner(Ingestor ingestor, VectorStore store, AskController ask, @Value("${app.top-k}") int topK) {
        this.ingestor = ingestor;
        this.store = store;
        this.ask = ask;
        this.topK = topK;
    }

    @Override
    public void run(String... args) throws Exception {
        ingestor.ingest();
        int n = 0, hit1 = 0, hitK = 0, answered = 0, cited = 0, refusedWrongly = 0, neg = 0, abstained = 0;
        double rr = 0;
        var in = new BufferedReader(new InputStreamReader(new ClassPathResource("eval.tsv").getInputStream(), StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            var a = ask.ask(new AskController.Question(f[0]));
            var v = a.verification();
            String oneLine = a.answer().replace('\n', ' ');
            if (f[1].equals("-")) { // unanswerable: the only right move is to abstain
                neg++; if (v.abstained()) abstained++;
                System.out.printf("%-58s %s%n", f[0], v.abstained() ? "abstained ok" : "ANSWERED: " + oneLine);
                continue;
            }
            List<Document> hits = store.similaritySearch(SearchRequest.builder().query(f[0]).topK(topK).build());
            int rank = 0;
            for (int i = 0; i < hits.size(); i++) if (f[1].equals(hits.get(i).getMetadata().get("source"))) { rank = i + 1; break; }
            String lower = a.answer().toLowerCase(Locale.ROOT);
            // any of the |-separated keywords; ponytail: keyword match, use an LLM judge when answers get longer
            boolean ok = java.util.Arrays.stream(f[2].split("\\|")).anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
            // grounded = every citation was retrieved, and the expected file is among them
            boolean citeOk = v.grounded() && !v.abstained() && v.cited().contains(f[1]);
            n++; if (rank == 1) hit1++; if (rank > 0) { hitK++; rr += 1.0 / rank; } if (ok) answered++; if (citeOk) cited++; if (v.abstained()) refusedWrongly++;
            System.out.printf("%-58s rank=%s answer=%s cite=%s%n", f[0], rank == 0 ? "-" : rank, ok ? "ok" : "MISS: " + oneLine,
                    citeOk ? "ok" : "BAD " + v.cited() + " " + v.issues());
        }
        System.out.printf(Locale.ROOT, "%nquestions=%d  hit@1=%d/%d  hit@%d=%d/%d  MRR=%.2f  answer-contains-keyword=%d/%d%n", n, hit1, n, topK, hitK, n, rr / n, answered, n);
        System.out.printf(Locale.ROOT, "cited-correct-source=%d/%d  false-refusals=%d/%d  unanswerable-abstained=%d/%d%n", cited, n, refusedWrongly, n, abstained, neg);
        System.exit(0);
    }
}
