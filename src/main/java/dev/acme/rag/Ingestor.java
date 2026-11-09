package dev.acme.rag;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/** Read -> split -> embed -> store. Re-running replaces each file's chunks, so ingest is idempotent. */
@Service
public class Ingestor {
    private static final Logger log = LoggerFactory.getLogger(Ingestor.class);
    private final VectorStore store;
    private final String pattern;
    // ponytail: 300-token chunks fit these short docs; tune size/overlap on your own eval set
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(300).withMinChunkSizeChars(100).build();

    public Ingestor(VectorStore store, @Value("${app.docs}") String pattern) {
        this.store = store;
        this.pattern = pattern;
    }

    public int ingest() throws IOException {
        int total = 0;
        for (Resource file : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            String source = file.getFilename();
            TextReader reader = new TextReader(file);
            reader.getCustomMetadata().put("source", source);
            List<Document> chunks = splitter.apply(reader.get());
            store.delete(new FilterExpressionBuilder().eq("source", source).build());
            store.add(chunks); // the EmbeddingModel is called here, one vector per chunk
            total += chunks.size();
            log.info("ingested {} -> {} chunk(s)", source, chunks.size());
        }
        return total;
    }
}
