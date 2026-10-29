# Spring AI DocumentReader for Office, PDF and Email with GroupDocs.Parser for Java

[![Product Page](https://img.shields.io/badge/Product%20Page-2865E0?style=for-the-badge&logo=appveyor&logoColor=white)](https://github.com/groupdocs-parser/GroupDocs.Parser-Docs)
[![Docs](https://img.shields.io/badge/Docs-2865E0?style=for-the-badge&logo=Hugo&logoColor=white)](https://docs.groupdocs.com/parser/java/)
[![Blog](https://img.shields.io/badge/Blog-2865E0?style=for-the-badge&logo=WordPress&logoColor=white)](https://blog.groupdocs.com/categories/groupdocs.parser-product-family/)
[![Free Support](https://img.shields.io/badge/Free%20Support-2865E0?style=for-the-badge&logo=Discourse&logoColor=white)](https://forum.groupdocs.com/c/parser/17)
[![Temporary License](https://img.shields.io/badge/Temporary%20License-2865E0?style=for-the-badge&logo=rocket&logoColor=white)](https://purchase.groupdocs.com/temp-license/100326)

## Introduction

spring-ai-document-reader-using-groupdocs-parser-java is a runnable Java 17 project that implements Spring AI's `DocumentReader` interface with GroupDocs.Parser for Java, so Word, PDF, email and ZIP files enter the Spring AI ETL pipeline as page-sized `Document` objects that know where they came from. Whether you are indexing a shared drive for an internal assistant or accepting uploads in a Spring Boot RAG service, the reader replaces per-format parsing code with one class that plugs in where `TikaDocumentReader` or `PagePdfDocumentReader` would normally sit.

## Use Case Scenarios

This repository addresses the following use cases:

- **Citable answers from long manuals**: a 40-page Word or PDF guide becomes one `Document` per page, each carrying a 1-based `page_number`.
- **Mailbox exports with attachments**: every attachment of an `.eml` or `.msg` file becomes its own `Document`, linked to its message through `parent_source`.
- **ZIP bundles**: each archive member is read as if it were on disk, with a `source` such as `sample.zip/sample.pdf`.
- **Uploads with unreliable names**: the format is detected from the stream content, so a blob without an extension still parses.

## The Problem

Spring AI splits ingestion into a reader, a transformer and a writer, and the [Spring AI ETL pipeline reference](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html) shows the whole flow in one line: `vectorStore.accept(tokenTextSplitter.apply(pdfReader.get()))`. That line is only as good as what the reader returns. If a ZIP comes back as one block of text, or an email comes back without its attachment, no splitter or embedding model can recover the missing structure later.

Spring AI's own readers cover the common cases: `TikaDocumentReader` is built on Apache Tika for formats such as PDF, DOC/DOCX, PPT/PPTX and HTML, and `PagePdfDocumentReader` on Apache PDFBox for PDF pages. A corpus that mixes containers, long paged files and anonymous streams adds requirements on the shape of the output, and meeting them by hand usually means a separate code path per format.

### Common Challenges

- ❌ Attachments and archive members are easy to lose, so answers cite the message that carried a file instead of the file itself.
- ❌ A whole manual stored as one `Document` gives every chunk the same citation and no page.
- ❌ Each format handled by its own glue code ends up with its own metadata names and its own failure modes.
- ❌ One unreadable file in a nightly batch can stop the whole indexing run.

## The Solution

GroupDocs.Parser for Java provides one in-process API for [65+ file formats](https://docs.groupdocs.com/parser/java/supported-document-formats/): open a stream, ask for the format and page count, read text per page, and list the items inside a container. `GroupDocsParserDocumentReader` maps those calls onto the Spring AI types, and nothing else in the pipeline needs to know which library produced the text. Key capabilities include:

✅ **Page-level Documents** - `getText(pageIndex)` runs for each page when `Features.isTextPage()` is true, and the page index is stored 1-based.
✅ **Attachment lineage** - `getContainer()` and `ContainerItem.openParser()` turn every attachment or archive member into its own Documents, with `parent_source` set.
✅ **Content-based detection** - the `Parser` is built from `resource.getInputStream()`, so any Spring `Resource` works.
✅ **Batch-safe reading** - `UnsupportedDocumentFormatException` becomes an empty list and a log line.
✅ **Clean embeddings** - blank pages and text-less items are dropped before a `Document` exists.

### When should I use this reader instead of TikaDocumentReader?

Use it when your corpus mixes Word, PDF, email and ZIP files and you want page numbers and attachment lineage in the `Document` metadata without writing per-format code. Keep `TikaDocumentReader` when its format coverage and output already match your retrieval needs. Both implement the same `DocumentReader` contract, so you can switch per resource type and compare the retrieved chunks on your own data before deciding.

I wanted a chat answer to cite `sample.zip/sample.pdf, page 2` rather than a bare file name, and that one requirement drove most of the metadata design in this project.

## Implementation Workflow

Here's a typical workflow for adding a Spring AI DocumentReader for Office, PDF and email to a service:

1. Add the dependencies: GroupDocs.Parser 26.10 from `https://releases.groupdocs.com/java/repo/` and `org.springframework.ai:spring-ai-commons:2.0.2` from Maven Central, on Java 17 or later.
2. Wrap each input in a Resource: a `FileSystemResource`, a classpath entry or an uploaded stream.
3. Read: call `new GroupDocsParserDocumentReader(resource).read()`; switch paging or attachments off through the three-argument constructor if needed.
4. Split: pass the Documents to `TokenTextSplitter`, which keeps the reader's metadata on every chunk.
5. Write: hand the chunks to your `VectorStore`; the demo writes JSON Lines instead so you can inspect them first.

## Requirements

To use these examples, you'll need:

- **Java 17 or later**: Spring AI 2.x does not run on older releases.
- **Apache Maven**: with access to Maven Central and the GroupDocs repository declared in `pom.xml`; run the demo with `mvn compile exec:java`.
- **License (optional)**: without one GroupDocs.Parser runs in evaluation mode; set the path in `applyLicense()` to remove the limits.

## Project Structure

```
spring-ai-document-reader-using-groupdocs-parser-java/
│
├── pom.xml
├── resources/
│   ├── email-with-attachment.eml
│   ├── sample.pdf
│   ├── sample.zip
│   └── SampleWithToc.docx
└── src/
    └── main/
        └── java/
            └── com/
                └── groupdocs/
                    └── demo/
                        ├── GroupDocsParserDocumentReader.java
                        └── SpringAiReaderDemo.java
```

The reader is one self-contained class you can copy into another project; the demo class and the `resources/` folder exist only to show it working end to end.

**File Organization:**
- `pom.xml` - Java release 17, GroupDocs.Parser 26.10, spring-ai-commons 2.0.2 and the exec plugin that starts `SpringAiReaderDemo`.
- `resources/email-with-attachment.eml` - an email with an `attachment.txt` file, used to show attachment lineage.
- `resources/sample.pdf` - a two-page PDF that produces one `Document` per page.
- `resources/sample.zip` - `sample.docx`, `sample.pdf`, `images.pdf` and two JPEG images; each member with text becomes its own `Document` set.
- `resources/SampleWithToc.docx` - a Word document with a table of contents, read page by page.
- `src/main/java/com/groupdocs/demo/GroupDocsParserDocumentReader.java` - the `DocumentReader`: two constructors, `get()`, `readBody` and `readAttachments`.
- `src/main/java/com/groupdocs/demo/SpringAiReaderDemo.java` - the console pipeline with `readAll`, `splitForEmbedding` and `writeJsonl`; writes `output/spring-ai-documents.jsonl` at run time.

## Practical Examples

### Use Case: Reads the resource into Spring AI Documents - the DocumentReader contract

**When to Use:** every time Spring AI calls `read()` on the reader, directly or from your ingestion job.

`get()` opens the resource as a stream, creates a GroupDocs.Parser `Parser` from it and collects the body Documents, followed by attachment Documents when `includeAttachments` is on. Stream input means the format comes from the bytes, so a misnamed upload still parses.

```java
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
```

**What This Solves:** one entry point for every format, with both the stream and the `Parser` closed by try-with-resources. I/O errors are rethrown as `UncheckedIOException` because `Supplier.get()` cannot declare checked exceptions.

**Real-World Application:** an upload endpoint can pass the multipart file's `Resource` straight to the reader without checking its extension first.

### Use Case: Extracts the text of an open document as one Document per page or one for the whole file

**When to Use:** for long Word and PDF files where answers must cite a page, or with paging off for short notes.

`readBody` reads the file type and page count from `getDocumentInfo()`. In page mode it loops over `getText(page)`; otherwise it reads the whole text once with `getText()`.

```java
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
```

**What This Solves:** every Document carries `source`, `file_type` (for example `.pdf`) and `page_count`, plus `page_number` in page mode. The private helpers `readText`, `metadata` and `addIfNotBlank` handle a null `TextReader`, skip blank text and keep null values out of metadata, which Spring AI rejects.

**Real-World Application:** a reviewer checking a generated answer opens the cited page of the specification directly.

### Use Case: Reads every email attachment or archive member into its own Documents

**When to Use:** for EML/MSG mailbox exports and ZIP bundles, where the useful text sits one level down.

`readAttachments` walks `getContainer()`, opens each `ContainerItem` with `openParser()` and sends it back through `readBody` with the parent's name. A non-container format returns `null` and exits early.

```java
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
```

**What This Solves:** attachments are indexed as first-class sources, and a member that cannot be parsed, such as an image without OCR, is skipped without affecting its siblings.

**Real-World Application:** a vector store filter on `parent_source` limits a search to one release bundle or one exported message.

### Use Case: Reads a batch of files into Spring AI Documents with the GroupDocs.Parser reader

**When to Use:** in a scheduled indexing job or any loop over a folder.

`readAll` wraps each file in a `FileSystemResource` and calls `read()` on a new reader, the same call a pipeline makes on `TikaDocumentReader`.

```java
List<Document> documents = new ArrayList<>();
for (File file : files) {
    GroupDocsParserDocumentReader reader =
            new GroupDocsParserDocumentReader(new FileSystemResource(file));
    documents.addAll(reader.read());
}
return documents;
```

**What This Solves:** swapping readers is a one-line change, because only the constructor differs. I kept the loop deliberately plain so the swap point is obvious in a code review, and so a test can run two readers over the same folder and diff what comes back.

**Real-World Application:** on the four bundled inputs this returns 12 Spring AI Documents: one per page plus one per attachment or archive member.

### Use Case: Splits Documents into token-sized chunks ready for an EmbeddingModel and a VectorStore

**When to Use:** right after reading, before embedding.

`splitForEmbedding` builds Spring AI's `TokenTextSplitter` with a chunk size in tokens and applies it to the reader output.

```java
TokenTextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(chunkSizeTokens)
        .build();
return splitter.apply(documents);
```

**What This Solves:** the splitter copies each Document's metadata to its chunks and adds `chunk_index`, `total_chunks` and `parent_document_id`, so citations and filters survive into the vector store.

**Real-World Application:** with `chunkSizeTokens` set to 300, the 12 Documents from the bundled run become 20 chunks.

### Use Case: Writes the chunks with their metadata as JSON Lines for inspection

**When to Use:** before you connect a real `VectorStore`, to check the metadata by eye.

`writeJsonl` stands in for `vectorStore.add(chunks)`. Each line holds the Document id, the metadata sorted by key and the chunk text, escaped by a small `json` helper so the sample needs no JSON library.

```java
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
```

**What This Solves:** a missing `parent_source` or an off-by-one page number shows up in the first ten lines of `output/spring-ai-documents.jsonl`, long before it shows up as a wrong citation in a chat answer.

**Real-World Application:** reading this file is the cheapest test there is before wiring an index that is expensive to rebuild.

## Benefits

By using GroupDocs.Parser for a Spring AI DocumentReader for Office, PDF and email, you gain:

- **One reader for mixed inputs**: Word, PDF, EML/MSG and ZIP go through the same class and the same metadata keys.
- **Citations with page numbers**: page mode is the default, so every chunk can point to a page.
- **Traceable attachments**: `parent_source` keeps each attachment tied to the message or archive that carried it.
- **On-premise processing**: parsing runs inside the JVM; no document is sent to an external service.
- **Unchanged pipeline**: `TokenTextSplitter` and your `VectorStore` are called exactly as before.

## Related Use Cases and Resources

If you're implementing a Spring AI DocumentReader for Office, PDF and email or working on similar scenarios, these resources will help you:

* **Spring AI DocumentReader Integration Guide for GroupDocs.Parser** - quickstart, integration patterns and a production checklist in the documentation: [Read the article](https://docs.groupdocs.com/parser/java/use-cases/spring-ai-document-reader-office-pdf-email/)

* **Stop Losing Email Attachments in a Spring AI RAG Index** - the before-and-after story behind this project: [Read the article](https://blog.groupdocs.com/parser/spring-ai-document-reader-office-pdf-email-java/)

* **Load a Document from a Stream in Java** - how `Parser` opens an `InputStream`, which the reader relies on: [Read the article](https://docs.groupdocs.com/parser/java/load-document-from-stream/)

* **Extract Data from Attachments and ZIP Archives** - the `getContainer` and `openParser` API used by `readAttachments`: [Read the article](https://docs.groupdocs.com/parser/java/extract-data-from-attachments-and-zip-archives/)

* **Extract ZIP Files Data in Java** - an earlier GroupDocs blog post on reading archive content: [Read the article](https://blog.groupdocs.com/parser/extract-zip-files-data-in-java/)

* **GroupDocs.Parser for Java 26.10 Release Highlights** - what changed in the version this project uses: [Read the article](https://blog.groupdocs.com/parser/groupdocs-parser-for-java-26-10/)

* **GroupDocs.Parser for Java API Reference** - `Parser`, `ContainerItem`, `TextReader` and the rest of the API: [Read the article](https://reference.groupdocs.com/parser/java/)

## Keywords

`Spring AI DocumentReader`, `Spring AI ETL pipeline`, `Tika alternative for Spring AI`, `read DOCX and PDF in Spring AI`, `GroupDocs.Parser for Java`, `RAG with Spring AI`, `TokenTextSplitter`, `parse email attachments in Java`, `Spring Boot RAG ingestion`, `Spring AI Document metadata`, `page_number metadata`, `parse ZIP archive in Java`, `EML attachment text extraction`, `vector store ingestion Java`, `Java 17`, `on-premise document parsing`, `DOCX to Spring AI Document`, `PDF page extraction Java`

**Ready to get started?** [View Documentation](https://docs.groupdocs.com/parser/java/) | [Get Support](https://forum.groupdocs.com/c/parser/17) | [Request License](https://purchase.groupdocs.com/temp-license/100326)
