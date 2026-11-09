# rag-starter

A small RAG service in Java: Spring Boot 4.1, Spring AI 2.0, pgvector and a local Ollama model. It has a measured eval harness and a few production-minded pieces around the retrieval core. It is a learning-scale project (6 short documents, 12 labelled questions), not a production system.

## What's in it

| Part | Where | What it does |
|---|---|---|
| Ingest | `Ingestor` | Read, split, embed, store. Re-running replaces a file's chunks, so ingest is idempotent. |
| Ask | `AskController` | `POST /ask`: retrieval advisor (top-k, similarity threshold), answer cites the source file. Says "I don't know" when nothing is retrieved. |
| Eval | `EvalRunner`, `eval.tsv` | Scores retrieval (hit@1, hit@k, MRR) and answers (expected keyword) on labelled questions. |
| LLM gateway | `gateway/LlmGateway` | Per-tenant token budget, exact then semantic cache, small/large model routing, fallback chain. Never caches time-sensitive questions. |
| Output guard | `security/OutputGuard` | Strips markdown images, links and e-mails to non-allowlisted hosts (blocks prompt-injection exfiltration). Deterministic. |
| MCP agent | `agent/AgentDemo`, `mcp/ops_server.py` | Java agent calling a Python MCP server: read-only ops tools plus one human-approved action (MCP elicitation). |
| Parsing | `ParseCompare` | Compares PDFBox and Tika text extraction on a PDF. |

## Run it

Requires JDK 21+, Maven, Docker, and [Ollama](https://ollama.com) with the two models below.

```bash
ollama pull llama3.2 && ollama pull nomic-embed-text
docker compose up -d            # pgvector on localhost:5433
mvn test                        # unit tests, no Ollama or DB needed
mvn spring-boot:run             # then:
curl -XPOST localhost:8080/ingest
curl -XPOST localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"Can I ship to production on Friday afternoon?"}'
```

Other profiles:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=eval        # retrieval + answer eval
mvn package -DskipTests && java -jar target/rag-starter-0.1.0.jar --spring.profiles.active=gateway --spring.main.web-application-type=none
java -jar target/rag-starter-0.1.0.jar --spring.profiles.active=agent --spring.main.web-application-type=none   # needs uv
```

## Measured results

Run on 2026-11-09, MacBook, local Ollama, chat model `gemma4`, embeddings `nomic-embed-text` (768 dims, HNSW, cosine), top-k 4, min similarity 0.5:

```
questions=12  hit@1=10/12  hit@4=12/12  MRR=0.92  answer-contains-keyword=12/12
```

Both hit@1 misses ranked the right file second. Read these numbers for what they are: 12 questions over 6 one-chunk documents is a smoke test, and the keyword check is lenient. `llama3.2` (the configured default) was not used for this run. To change the model: `--spring.ai.ollama.chat.options.model=<name>`.

## Tests

2 test classes, 6 tests, all passing with `mvn test` (JDK 25):
- `LlmGatewayTest`: budget, exact and semantic cache hits, tenant isolation, fallback.
- `OutputGuardTest`: link, image and e-mail stripping.

## Limitations

- Token budget and caches are in-process; use Redis or an API gateway across replicas.
- Chunking (300 tokens) is tuned to the tiny sample docs, not to real corpora.
- Keyword-based answer scoring; an LLM judge would be needed for longer answers.
- Local-only: no auth, no rate limiting on the HTTP endpoints.
- Sample documents and the ops knowledge base are fictional.

## License

MIT, see `LICENSE`.
