package com.groupdocs.demo;

// Topic: A Spring AI DocumentReader backed by GroupDocs.Parser for Java.
// Reads Word, PDF, email and ZIP resources into Spring AI Documents - one per page, plus
// one per attachment or archive member - with source, page and lineage metadata. Runs
// on-premise.

import com.groupdocs.parser.Parser;
import com.groupdocs.parser.data.ContainerItem;
import com.groupdocs.parser.data.TextReader;
import com.groupdocs.parser.exceptions.UnsupportedDocumentFormatException;
import com.groupdocs.parser.options.IDocumentInfo;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroupDocsParserDocumentReader implements DocumentReader {

    public static final String METADATA_SOURCE = "source";
    public static final String METADATA_PARENT = "parent_source";
    public static final String METADATA_FILE_TYPE = "file_type";
    public static final String METADATA_PAGE = "page_number";
    public static final String METADATA_PAGE_COUNT = "page_count";

    private final Resource resource;
    private final boolean pagePerDocument;
    private final boolean includeAttachments;

    /**
     * Creates a reader that emits one Document per page and reads attachments too.
     * @remarks The defaults suit retrieval: page-sized Documents give citations a page
     *          number, and attachments of an email or members of a ZIP are often where the
     *          facts live. Any Spring Resource works - a file, a classpath entry or an
     *          uploaded stream.
     */
    public GroupDocsParserDocumentReader(Resource resource) {
        this(resource, true, true);
    }

    /**
     * Creates a reader with explicit page and attachment handling.
     * @remarks pagePerDocument=false returns the whole body as one Document, which is what
     *          a TokenTextSplitter downstream usually expects for short files.
     *          includeAttachments=false ignores email attachments and archive members.
     */
    public GroupDocsParserDocumentReader(Resource resource, boolean pagePerDocument,
                                         boolean includeAttachments) {
        this.resource = resource;
        this.pagePerDocument = pagePerDocument;
        this.includeAttachments = includeAttachments;
    }

    /**
     * Reads the resource into Spring AI Documents - the DocumentReader contract.
     * @remarks Opens the resource stream and creates a GroupDocs.Parser Parser from it; the
     *          format is detected from the content, so a missing or wrong extension still
     *          parses. Body text comes from readBody, attachments and archive members from
     *          readAttachments. A format Parser cannot read raises
     *          UnsupportedDocumentFormatException, which is reported as an empty list so
     *          one bad file never breaks an ETL batch.
     */
    @Override
    public List<Document> get() {
        String source = resource.getFilename() != null
                ? resource.getFilename() : "resource";
        try (InputStream stream = resource.getInputStream();
             Parser parser = new Parser(stream)) {
            List<Document> documents = new ArrayList<>(readBody(parser, source, null));
            if (includeAttachments) {
                documents.addAll(readAttachments(parser, source));
            }
            return documents;
        } catch (UnsupportedDocumentFormatException ex) {
            System.out.println("skipped (unsupported format): " + source);
            return new ArrayList<>();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Extracts the text of an open document as one Document per page or one for the whole
     * file.
     * @remarks Uses getDocumentInfo for the file type and page count. When
     *          Features.isTextPage is true and paging is on, it calls getText(pageIndex)
     *          for every page and stores a 1-based page_number; otherwise it calls getText
     *          once. A null TextReader means the format has no text (an image without OCR),
     *          and empty pages are skipped so the vector store gets no blank embeddings.
     */
    protected List<Document> readBody(Parser parser, String source, String parent) {
        List<Document> documents = new ArrayList<>();
        IDocumentInfo info = parser.getDocumentInfo();
        String fileType = info.getFileType().getExtension();
        int pageCount = info.getPageCount();

        if (pagePerDocument && pageCount > 0 && parser.getFeatures().isTextPage()) {
            for (int page = 0; page < pageCount; page++) {
                String text = readText(parser.getText(page));
                Map<String, Object> metadata =
                        metadata(source, parent, fileType, pageCount);
                metadata.put(METADATA_PAGE, page + 1);
                addIfNotBlank(documents, text, metadata);
            }
        } else {
            String text = readText(parser.getText());
            addIfNotBlank(documents, text, metadata(source, parent, fileType, pageCount));
        }
        return documents;
    }

    /**
     * Reads every email attachment or archive member into its own Documents.
     * @remarks getContainer returns the attachments of an EML/MSG message, the entries of a
     *          ZIP, or null when the format is not a container. Each ContainerItem is
     *          opened with openParser and read by readBody; the Documents keep the parent's
     *          name in parent_source and their own path in source, e.g.
     *          "mail.eml/invoice.pdf", so a retrieved chunk can always be traced back to
     *          the message that carried it.
     */
    protected List<Document> readAttachments(Parser parser, String source) {
        List<Document> documents = new ArrayList<>();
        Iterable<ContainerItem> items = parser.getContainer();
        if (items == null) {
            return documents;
        }
        for (ContainerItem item : items) {
            String childSource = source + "/" + item.getFilePath();
            try (Parser child = item.openParser()) {
                documents.addAll(readBody(child, childSource, source));
            } catch (UnsupportedDocumentFormatException ex) {
                System.out.println("skipped (unsupported format): " + childSource);
            }
        }
        return documents;
    }

    private static Map<String, Object> metadata(String source, String parent,
                                                String fileType, int pageCount) {
        // Spring AI rejects null metadata values, so optional keys are only added when set.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(METADATA_SOURCE, source);
        metadata.put(METADATA_FILE_TYPE, fileType == null ? "" : fileType);
        metadata.put(METADATA_PAGE_COUNT, pageCount);
        if (parent != null) {
            metadata.put(METADATA_PARENT, parent);
        }
        return metadata;
    }

    private static void addIfNotBlank(List<Document> documents, String text,
                                      Map<String, Object> metadata) {
        if (text != null && !text.isBlank()) {
            documents.add(new Document(text.strip(), metadata));
        }
    }

    private static String readText(TextReader reader) {
        if (reader == null) {
            return null;
        }
        try (reader) {
            return reader.readToEnd();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
