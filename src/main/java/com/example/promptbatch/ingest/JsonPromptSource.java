package com.example.promptbatch.ingest;

import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.model.Prompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses {@code {"prompts": ["...", "..."]}} JSON payloads. */
public class JsonPromptSource implements PromptSource {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<Prompt> parse(String batchId, InputStream raw) throws BadInputException {
        try {
            Map<String, Object> payload = mapper.readValue(raw, Map.class);
            Object promptsField = payload.get("prompts");
            if (!(promptsField instanceof List<?> rawList) || rawList.isEmpty()) {
                throw new BadInputException("JSON payload must contain a non-empty \"prompts\" array");
            }
            List<Prompt> prompts = new ArrayList<>(rawList.size());
            int i = 0;
            for (Object item : rawList) {
                prompts.add(new Prompt(batchId + "-p" + (i++), String.valueOf(item)));
            }
            return prompts;
        } catch (IOException e) {
            throw new BadInputException("Unable to parse JSON prompt payload: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("application/json");
    }
}
