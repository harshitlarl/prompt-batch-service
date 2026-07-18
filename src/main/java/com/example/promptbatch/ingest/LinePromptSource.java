package com.example.promptbatch.ingest;

import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.model.Prompt;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses one-prompt-per-line uploads (blank lines are skipped). */
public class LinePromptSource implements PromptSource {

    @Override
    public List<Prompt> parse(String batchId, InputStream raw) throws BadInputException {
        List<Prompt> prompts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            int i = 0;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                prompts.add(new Prompt(batchId + "-p" + (i++), trimmed));
            }
        } catch (IOException e) {
            throw new BadInputException("Unable to read line-delimited prompt upload: " + e.getMessage());
        }
        if (prompts.isEmpty()) {
            throw new BadInputException("Upload contained no prompts");
        }
        return prompts;
    }

    @Override
    public boolean supports(String contentType) {
        return contentType != null
                && (contentType.toLowerCase().contains("text/plain")
                        || contentType.toLowerCase().contains("application/octet-stream"));
    }
}
