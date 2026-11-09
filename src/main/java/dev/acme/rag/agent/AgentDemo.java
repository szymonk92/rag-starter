package dev.acme.rag.agent;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;

/** A Java agent using the Python MCP server: java -jar app.jar --spring.profiles.active=agent --spring.main.web-application-type=none */
@Configuration
@Profile("agent")
public class AgentDemo {

    /** Answers the server's approval question (MCP elicitation). Stands in for a human: logs the request and approves. */
    @Bean
    McpClientCustomizer<McpClient.SyncSpec> approvals() {
        return (server, spec) -> spec
                // a handler alone is not enough: the client must declare the capability, or the server refuses to ask
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build()) // form yes, URL no
                .elicitation(req -> {
            System.out.println("  [approval asked by " + server + "] " + req.message() + " -> approve");
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("approve", true));
        });
    }

    @Bean
    ApplicationRunner runAgent(ChatModel chatModel, SyncMcpToolCallbackProvider mcpTools) {
        return (ApplicationArguments args) -> {
            var tools = mcpTools.getToolCallbacks();
            System.out.println("tools from MCP: " + java.util.Arrays.stream(tools).map(t -> t.getToolDefinition().name()).toList());
            var chat = ChatClient.builder(chatModel)
                    .defaultSystem("You are an ops assistant. Use the tools to look up facts; never guess IDs, names or owners. "
                            + "Only create tickets when the user asks for one. Answer briefly once you have the facts.")
                    .defaultOptions(OllamaChatOptions.builder().model("qwen2.5:14b").temperature(0.0)) // 2.0 takes the builder
                    .defaultToolCallbacks(tools) // Spring AI runs the tool loop: model -> tool -> model ... until a text answer
                    .build();
            for (String q : new String[]{"Who manages the team that owns the service affected by INC-42?",
                    "Which services break if auth-service goes down?",
                    "Open a ticket for the team that owns orders-db: investigate replica lag. Use idempotency key java-demo-1.",
                    "Open a ticket for the Data Platform team: investigate replica lag. Use idempotency key java-demo-2."}) {
                long t0 = System.nanoTime();
                String answer = chat.prompt().user(q).call().content();
                System.out.printf("Q: %s%nA: %s  (%d ms)%n", q, answer.replace('\n', ' '), (System.nanoTime() - t0) / 1_000_000);
            }
            System.exit(0);
        };
    }
}
