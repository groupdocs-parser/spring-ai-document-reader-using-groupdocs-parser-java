package com.groupdocs.demo;

// Topic: Feed a Spring AI ETL pipeline from GroupDocs.Parser instead of Tika.
// Reads every file in resources/ with GroupDocsParserDocumentReader, splits the Documents
// with Spring AI's TokenTextSplitter and writes what a VectorStore would receive.

import com.groupdocs.parser.licensing.License;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SpringAiReaderDemo {

    private static final String DOCS = "resources";
    private static final String RESULT = "output";
    // Keeps the splitter's per-document INFO lines out of the console output.
    private static final Logger SPRING_AI_LOG = Logger.getLogger("org.springframework.ai");

    public static void main(String[] args) throws Exception {
        SPRING_AI_LOG.setLevel(Level.WARNING);
        new File(DOCS).mkdirs();
        new File(RESULT).mkdirs();
        applyLicense();

        File[] files = new File(DOCS).listFiles(File::isFile);
        if (files == null || files.length == 0) {
            System.err.println("No input documents in " + new File(DOCS).getAbsolutePath());
            System.exit(1);
        }
        Arrays.sort(files);

        List<Document> documents = readAll(files);
        List<Document> chunks = splitForEmbedding(documents, 300);

        Map<String, Integer> perSource = new TreeMap<>();
        for (Document chunk : chunks) {
            Object source =
                    chunk.getMetadata().get(GroupDocsParserDocumentReader.METADATA_SOURCE);
            perSource.merge(String.valueOf(source), 1, Integer::sum);
        }
        perSource.forEach((source, count) ->
                System.out.println(count + " chunks <- " + source));

        String output = Paths.get(RESULT, "spring-ai-documents.jsonl").toString();
        writeJsonl(chunks, output);
        System.out.println("Documents read: " + documents.size()
                + ", chunks: " + chunks.size());
        System.out.println("Written: " + new File(output).getAbsolutePath());

        System.exit(chunks.isEmpty() ? 2 : 0);
    }

    private static void applyLicense() {
        // Point this at your .lic file to remove evaluation limits.
        // Get a free temporary licence: https://purchase.groupdocs.com/temporary-license
        String licensePath = "REPLACE_WITH_YOUR_LICENSE_PATH";
        if (new File(licensePath).exists()) {
            new License().setLicense(licensePath);
            System.out.println("[license] applied");
        } else {
            System.out.println("[license] no licence set - running in evaluation mode");
        }
    }

    /**
     * Reads a batch of files into Spring AI Documents with the GroupDocs.Parser reader.
     * @remarks Wraps each file in a FileSystemResource and calls DocumentReader.read() on a
     *          GroupDocsParserDocumentReader - the same call a Spring AI ETL pipeline makes
     *          on TikaDocumentReader, so swapping readers is a one-line change. In a Spring
     *          Boot app the resources would come from a ResourceLoader or an upload
     *          instead.
     */
    static List<Document> readAll(File[] files) {
        List<Document> documents = new ArrayList<>();
        for (File file : files) {
            GroupDocsParserDocumentReader reader =
                    new GroupDocsParserDocumentReader(new FileSystemResource(file));
            documents.addAll(reader.read());
        }
        return documents;
    }

    /**
     * Splits Documents into token-sized chunks ready for an EmbeddingModel and a
     * VectorStore.
     * @remarks Applies Spring AI's TokenTextSplitter, built with a chunk size in tokens.
     *          The splitter copies each Document's metadata to its chunks, so source,
     *          page_number and parent_source set by the reader survive into the vector
     *          store and can be used for citations and metadata filters at query time.
     */
    static List<Document> splitForEmbedding(List<Document> documents, int chunkSizeTokens) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSizeTokens)
                .build();
        return splitter.apply(documents);
    }

    /**
     * Writes the chunks with their metadata as JSON Lines for inspection.
     * @remarks Stands in for vectorStore.add(chunks): each line holds the Document id, its
     *          metadata and its text. Strings are escaped by hand to keep the sample free
     *          of a JSON library, and the file is written as UTF-8.
     */
    static void writeJsonl(List<Document> chunks, String outputPath) throws IOException {
        try (Writer out = Files.newBufferedWriter(Paths.get(outputPath),
                StandardCharsets.UTF_8)) {
            for (Document chunk : chunks) {
                StringBuilder line = new StringBuilder("{\"id\":")
                        .append(json(chunk.getId()));
                line.append(",\"metadata\":{");
                boolean first = true;
                Map<String, Object> metadata = new TreeMap<>(chunk.getMetadata());
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    line.append(first ? "" : ",").append(json(entry.getKey())).append(':');
                    Object value = entry.getValue();
                    line.append(value instanceof Number
                            ? value.toString() : json(String.valueOf(value)));
                    first = false;
                }
                line.append("},\"text\":").append(json(chunk.getText())).append("}\n");
                out.write(line.toString());
            }
        }
    }

    private static String json(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
