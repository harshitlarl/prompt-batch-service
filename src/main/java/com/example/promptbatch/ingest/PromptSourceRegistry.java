package com.example.promptbatch.ingest;

import com.example.promptbatch.exception.BadInputException;
import java.util.List;

/**
 * Selects a {@link PromptSource} by content type. Adding a new input format is "register a
 * new {@code PromptSource}" - the resource and service that use this registry never change
 * (LLD.md §5.3, the Strategy + Registry pattern).
 */
public class PromptSourceRegistry {

    private final List<PromptSource> sources;

    public PromptSourceRegistry(List<PromptSource> sources) {
        this.sources = sources;
    }

    public PromptSource select(String contentType) {
        return sources.stream()
                .filter(s -> s.supports(contentType))
                .findFirst()
                .orElseThrow(() -> new BadInputException("Unsupported content type: " + contentType));
    }
}
