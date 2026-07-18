package com.example.promptbatch.ingest;

import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.model.Prompt;
import java.io.InputStream;
import java.util.List;

/**
 * Seam S3 (LLD.md §4): turns a raw input payload into {@code List<Prompt>}. Each input
 * format (JSON body, line-delimited file upload, CSV, ...) is a separate implementation,
 * selected by content type via {@link PromptSourceRegistry} - adding a format never requires
 * editing the resource or service.
 */
public interface PromptSource {

    /** Parses a raw payload into prompts, assigning stable prompt ids under {@code batchId}. */
    List<Prompt> parse(String batchId, InputStream raw) throws BadInputException;

    /** Whether this source can handle the given media type. */
    boolean supports(String contentType);
}
