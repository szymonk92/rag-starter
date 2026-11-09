package dev.acme.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/** java -jar app.jar --spring.profiles.active=parse --spring.main.web-application-type=none <pdf> <outDir> */
@Component
@Profile("parse")
public class ParseCompare implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) throws Exception {
        var files = args.getNonOptionArgs(); // skips --spring.* options
        var pdf = new FileSystemResource(files.get(0));
        Path out = Path.of(files.get(1));
        write(out.resolve("spring-pdfbox.txt"), new PagePdfDocumentReader(pdf).get().stream()); // one Document per page
        write(out.resolve("spring-tika.txt"), new TikaDocumentReader(pdf).get().stream());
        System.exit(0);
    }

    private static void write(Path file, java.util.stream.Stream<Document> docs) throws Exception {
        // getBytes replaces malformed characters (PDF extraction can emit lone surrogates); writeString would throw
        Files.write(file, docs.map(Document::getText).collect(Collectors.joining("\n\n---page---\n\n")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("wrote " + file);
    }
}
