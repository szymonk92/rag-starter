# Images, equations, and alternatives

How this repo handles images and formulas, how to check the RAG returns them correctly, and what to use instead at larger scale.

## What this repo does (implemented and measured)

| Content | Stored as | Returned how | Checked by |
|---|---|---|---|
| Formula | LaTeX kept in the chunk text (`$...$`, `$$...$$`), e.g. `docs/sla.md` | Model copies it (system prompt says verbatim) | `AnswerCheck`: formula must appear in the retrieved context, whitespace-insensitive. Rewritten but equivalent formulas: `eval/latex_equiv.py` |
| Image | Markdown `![caption](path)` in the chunk; the caption is what gets embedded | `images` field of `/ask`, filled **from the retrieved chunks, not from the model** | `AnswerCheck`: any image path in the text must exist in context. Eval: expected path in `images` |
| Number | Plain text | Model text | `AnswerCheck`: every number in the answer must appear in context or question (`4,000` = `4000`) |

The eval run that led to the `images` field: the model answered "Yes, there is an image titled …" with the right caption and no path. Models describe pictures but drop or garble file names, so the path comes from the chunk metadata instead.

## Storing images: three approaches

1. **Caption → text embedding** (this repo). A vision model writes a description at ingest; you embed the description and keep the image path in metadata. Works with any text vector store and text eval. Retrieval is only as good as the caption. Locally: Ollama `qwen3-vl:8b-instruct` (already pulled on this machine) or Docling's picture-description step.
2. **Multimodal embeddings.** Image and text in one vector space (CLIP / SigLIP family, `jina-embeddings-v4`, Cohere Embed v4, Voyage multimodal). Search an image by a text query without captions. Needs a model that serves both modalities; most local Ollama embedders are text-only.
3. **Page-image retrieval (ColPali / ColQwen).** Skip parsing: embed screenshots of whole pages with a late-interaction vision model, retrieve pages, let a VLM answer from the picture. Strong for slides, scans and charts; needs multi-vector storage (Vespa, Qdrant multivector, or the `byaldi` wrapper). One 2025 study found an OCR pipeline beat ColQwen2 on retrieval and answer accuracy for unseen documents, while the vision pipeline was cheaper to embed ([arXiv 2505.05666](https://arxiv.org/abs/2505.05666)). Measure on your own documents.

Default: 1, add 3 when documents are visual-first (slides, scanned forms, charts with no text).

## Storing equations

- Keep the **LaTeX** in the chunk; never store only the rendered glyphs (PDF text extraction turns `\frac{a}{b}` into `a b` on two lines).
- Raw LaTeX embeds poorly. Put a sentence next to it saying what it computes ("The error budget is …"). Embed the prose, return the LaTeX.
- Don't let the splitter cut inside `$$...$$`. The 300-token splitter here never hits it on these small docs; on real PDFs, split on paragraph or element boundaries from the parser.
- Getting LaTeX out of PDFs needs a math-aware parser. PDFBox/Tika (`ParseCompare`) do not produce LaTeX.

## Checking that the RAG outputs them correctly

| Layer | Check | Tool |
|---|---|---|
| Parsing | Extracted LaTeX vs hand-written ground truth for a sample of pages: exact match after whitespace normalisation, then `latex_equiv.py` | this repo; OmniDocBench is the public benchmark for parsers |
| Retrieval | hit@k / MRR with questions whose answer is a formula or an image | `EvalRunner` (`sla.md` rows) |
| Answer, formula | Verbatim in context → pass. Not verbatim → symbolic equivalence (`sympy`), then random-point numeric check | `AnswerCheck`, `eval/latex_equiv.py` |
| Answer, image | Path comes from chunks (cannot be invented); eval checks the expected path is in `images` | `/ask` `images`, `EvalRunner` |
| Answer, numbers | Every number traceable to context or question | `AnswerCheck` |
| Answer, claims | Is each sentence supported by the context (faithfulness) | LLM judge: Spring AI `FactCheckingEvaluator`, RAGAS faithfulness, DeepEval |
| Rendering | LaTeX in the answer actually renders (KaTeX/MathJax throws on bad syntax) | KaTeX `renderToString` with `throwOnError: true` in a UI test |

`latex_equiv.py` gotchas found while writing it: sympy reads `SLO` as `S*L*O` and `T(1-SLO)` as a call to a function `T`; `normalize()` fixes both. `\left(` is not handled.

## Alternative tools

**Parsing (formulas, images, tables):**

| Tool | Good at | Notes |
|---|---|---|
| Docling (IBM, open source) | Layout, tables, formula → LaTeX (`do_formula_enrichment`), picture description via a VLM | Python; enrichment steps are slow. Can be called as a service from Java |
| Marker | PDF → Markdown with LaTeX math | Python, GPU helps |
| MinerU | Scientific PDFs, formula detection and recognition | Python, heavy models |
| Mathpix | Best-known commercial math OCR | Paid API |
| VLM OCR (Qwen-VL, olmOCR, Mistral OCR) | Scans, handwriting, odd layouts | Prompt it for Markdown + LaTeX; check output, it can hallucinate |
| PDFBox / Tika (here) | Plain text PDFs | No math, no image captions |

**Frameworks:** Spring AI (here) · LangChain4j (the other Java option, similar building blocks) · LlamaIndex (Python, strongest on ingestion and multimodal indexes) · LangChain / LangGraph · Haystack (Python, explicit pipelines).

**Vector stores:** pgvector (here; fine to millions of vectors, one database) · Qdrant (multivector for ColPali, filtering) · Weaviate · Milvus · Elasticsearch / OpenSearch (BM25 + vector hybrid in one engine) · Vespa (late interaction at scale).

**Retrieval upgrades:** hybrid BM25 + vector (helps exact terms like `SEV1`, `e-ZLA`), and a cross-encoder reranker (e.g. `bge-reranker`) over the top 20. Both hit@1 misses here ranked the right file second, which is the case a reranker fixes.

**Evaluation:**

| Tool | Use it for | Notes |
|---|---|---|
| RAGAS | Widest set of RAG metrics (faithfulness, context precision/recall) | Python; maintenance reported as slowing in 2026, check before adopting |
| DeepEval | pytest-style assertions that fail CI | Python |
| promptfoo | YAML test suites, model comparison, red-teaming | CLI, language-agnostic |
| Spring AI evaluators | `RelevancyEvaluator`, `FactCheckingEvaluator` inside Java tests | Fewer metrics |
| Phoenix / Langfuse / LangSmith | Tracing and dashboards for production traffic | Pair with one of the above |

LLM-judge metrics need their own small labelled set, or you are trusting one model to grade another.

Sources: [Docling enrichments](https://docling-project.github.io/docling/usage/enrichments/), [Docling formula example](https://docling-project.github.io/docling/examples/develop_formula_understanding), [OCR vs vision retrieval study](https://arxiv.org/abs/2505.05666), [DeepEval vs promptfoo vs RAGAS 2026](https://qaskills.sh/blog/deepeval-vs-ragas-vs-promptfoo-2026), [RAG eval tools 2026](https://ecorpit.com/rag-evaluation-tools-ragas-deepeval-braintrust-giskard-2026/).
