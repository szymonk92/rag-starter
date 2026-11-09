package dev.acme.rag.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** java -jar app.jar --spring.profiles.active=gateway --spring.main.web-application-type=none */
@Component
@Profile("gateway")
public class GatewayDemo implements ApplicationRunner {
    private final ChatModel chat; private final EmbeddingModel embedding;
    public GatewayDemo(ChatModel chat, EmbeddingModel embedding) { this.chat = chat; this.embedding = embedding; }

    @Override
    public void run(ApplicationArguments args) {
        LlmGateway.ModelClient client = (model, prompt) -> { // one ChatModel bean, model chosen per request
            var res = chat.call(new Prompt(prompt, OllamaChatOptions.builder().model(model).temperature(0.0).numPredict(120).build()));
            return new LlmGateway.Reply(res.getResult().getOutput().getText().strip(), res.getMetadata().getUsage().getTotalTokens());
        };
        var gw = new LlmGateway(client, Map.of("small", List.of("no-such-model", "llama3.2"), "large", List.of("qwen2.5:14b")),
                new LlmGateway.SemanticCache(embedding::embed, 0.85),
                Map.of("acme", new LlmGateway.TokenBudget(100_000), "trial", new LlmGateway.TokenBudget(800)), 400);
        String[][] reqs = {{"acme", "What is the capital of Poland?"}, {"acme", "what is the capital of poland?"},
                {"acme", "Which city is the capital of Poland?"}, {"acme", "Explain why HTTP/2 multiplexing reduces latency, in two sentences."},
                {"acme", "Is the service down now?"}, {"trial", "Name one prime number."}, {"trial", "Name another prime number."},
                {"trial", "And one more prime number?"}};
        for (String[] r : reqs) {
            long t0 = System.nanoTime();
            var res = gw.ask(r[0], r[1]);
            String a = res.answer().replace('\n', ' ');
            System.out.printf(java.util.Locale.ROOT, "%-6s %d %-20s route=%-5s tried=%s sim=%.3f tokens=%d ms=%d | %s%n", r[0], res.status(), res.model(), res.route(),
                    res.tried(), res.sim(), res.tokens(), (System.nanoTime() - t0) / 1_000_000, a.substring(0, Math.min(70, a.length())));
        }
        System.exit(0);
    }
}
