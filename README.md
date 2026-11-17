# rag-starter

A small RAG service in Java: Spring Boot 4.1, Spring AI 2.0, pgvector and a local Ollama model. It has a measured eval harness and a few production-minded pieces around the retrieval core. It is a learning-scale project (6 short documents, 12 labelled questions), not a production system.

## What's in it

| Part | Where | What it does |
|---|---|---|
| Ingest | `Ingestor` | Read, split, embed, store. Re-running replaces a file's chunks, so ingest is idempotent. |
| Ask | `AskController` | `POST /ask`: retrieval advisor (top-k, similarity threshold), answer cites the source file. Says "I don't know" when nothing is retrieved. |
| Answer check | `verify/AnswerCheck` | Every `/ask` response carries `verification`: `grounded` is false when the answer has no citation, cites a file that was not retrieved, answers with empty context, or contains a number, LaTeX formula or image path that is not in the retrieved text; `abstained` marks refusals. Runs after `OutputGuard`. Flags, does not block. |
| Eval | `EvalRunner`, `eval.tsv` | Scores retrieval (hit@1, hit@k, MRR), answers (expected keyword), citations (cites the expected file, nothing un-retrieved) and abstention on unanswerable questions. |
| LLM gateway | `gateway/LlmGateway` | Per-tenant token budget, exact then semantic cache, small/large model routing, fallback chain. Never caches time-sensitive questions. |
| Images | `AskController` | `images` in `/ask` lists `![caption](path)` found in the retrieved chunks, so image paths never depend on the model. See [MULTIMODAL.md](MULTIMODAL.md). |
| Output guard | `security/OutputGuard` | Applied to every `/ask` answer (allowlist `app.allowed-hosts`). Strips markdown images, links and e-mails to non-allowlisted hosts (blocks prompt-injection exfiltration). Deterministic. |
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

Run on 2026-11-17, MacBook, local Ollama, chat model `gemma4`, embeddings `nomic-embed-text` (768 dims, HNSW, cosine), top-k 4, min similarity 0.5:

```
questions=16  hit@1=14/16  hit@4=16/16  MRR=0.94  answer-contains-keyword=16/16
grounded-with-expected-source=16/16  false-refusals=0/16  unanswerable-abstained=4/4
```

Both hit@1 misses ranked the right file second. Four questions target `sla.md` (two LaTeX formulas, a computed number, an image); before the `images` field existed the model described the chart but never gave its path (15/16). The 4 unanswerable questions are near-topic on purpose (parental leave, hotel limit, canary at 50%), so retrieval returns chunks and the model itself has to refuse. Read these numbers for what they are: 20 questions over 7 one-chunk documents is a smoke test, and the keyword check is lenient. `llama3.2` (the configured default) was not used for this run. To change the model: `--spring.ai.ollama.chat.options.model=<name>`.

## Tests

3 test classes, 17 tests, all passing with `mvn test` (JDK 24), plus `eval/latex_equiv.py` (6 asserts, `uv run --with sympy==1.14.0 --with antlr4-python3-runtime==4.11 python eval/latex_equiv.py`):
- `LlmGatewayTest`: budget, exact and semantic cache hits, tenant isolation, fallback.
- `OutputGuardTest`: link, image and e-mail stripping.
- `AnswerCheckTest`: citation outside context, missing citation, empty context, refusal phrasings, path/case in citations, changed numbers, altered formulas, invented image paths.

## Limitations

- Token budget and caches are in-process; use Redis or an API gateway across replicas.
- Chunking (300 tokens) is tuned to the tiny sample docs, not to real corpora.
- Keyword-based answer scoring; an LLM judge would be needed for longer answers.
- `AnswerCheck` verifies citations, not claims: a wrong statement attributed to a retrieved file passes. Next step is a claim-level groundedness judge (e.g. Spring AI `FactCheckingEvaluator`). Alternatives for parsing, images, equations and evaluation: [MULTIMODAL.md](MULTIMODAL.md).
- Local-only: no auth, no rate limiting on the HTTP endpoints.
- Sample documents and the ops knowledge base are fictional.

## License

MIT, see `LICENSE`.
