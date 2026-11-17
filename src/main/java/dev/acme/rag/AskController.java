package dev.acme.rag;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.acme.rag.security.OutputGuard;
import dev.acme.rag.verify.AnswerCheck;

@RestController
public class AskController {
    record Question(String question) {}
    record Source(String source, double score) {}
    record Verification(boolean grounded, boolean abstained, List<String> cited, List<String> issues, List<String> removed) {}
    record Answer(String answer, List<Source> sources, Verification verification) {}

    private final ChatClient chat;
    private final Ingestor ingestor;
    private final OutputGuard guard;

    public AskController(ChatClient.Builder builder, VectorStore store, Ingestor ingestor,
                         @Value("${app.top-k}") int topK, @Value("${app.min-similarity}") double minSimilarity,
                         @Value("${app.allowed-hosts}") Set<String> allowedHosts) {
        this.ingestor = ingestor;
        this.guard = new OutputGuard(allowedHosts);
        var rag = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(store).topK(topK).similarityThreshold(minSimilarity).build())
                // label each chunk with its file so the model can cite it; empty context -> the default "I don't know" prompt
                .queryAugmenter(ContextualQueryAugmenter.builder().documentFormatter(AskController::format).build())
                .build();
        this.chat = builder
                .defaultSystem("Answer only from the context. Cite the source file in square brackets, e.g. [deploys.md]. Be brief.")
                .defaultAdvisors(rag)
                .build();
    }

    static String format(List<Document> docs) {
        return docs.stream().map(d -> "[" + d.getMetadata().get("source") + "]\n" + d.getText()).collect(Collectors.joining("\n\n"));
    }

    @PostMapping("/ingest")
    public int ingest() throws IOException {
        return ingestor.ingest();
    }

    @PostMapping("/ask")
    public Answer ask(@RequestBody Question q) {
        ChatClientResponse r = chat.prompt().user(q.question()).call().chatClientResponse();
        @SuppressWarnings("unchecked")
        List<Document> used = (List<Document>) r.context().getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
        var sources = used.stream().map(d -> new Source((String) d.getMetadata().get("source"), d.getScore())).toList();
        // guard first (injected docs can make the model emit exfiltration links), then check citations against what was retrieved
        var guarded = guard.apply(r.chatResponse().getResult().getOutput().getText());
        var c = AnswerCheck.check(guarded.text(), sources.stream().map(Source::source).toList());
        // ponytail: flags, does not block; callers decide whether to show an ungrounded answer
        return new Answer(guarded.text(), sources, new Verification(c.grounded(), c.abstained(), c.cited(), c.issues(), guarded.removed()));
    }
}
