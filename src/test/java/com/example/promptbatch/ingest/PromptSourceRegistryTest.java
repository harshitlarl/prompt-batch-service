package com.example.promptbatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.promptbatch.exception.BadInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptSourceRegistryTest {

    private final PromptSourceRegistry registry =
            new PromptSourceRegistry(List.of(new JsonPromptSource(), new LinePromptSource()));

    @Test
    void selectsJsonSourceForJsonContentType() {
        assertThat(registry.select("application/json")).isInstanceOf(JsonPromptSource.class);
    }

    @Test
    void selectsLineSourceForPlainText() {
        assertThat(registry.select("text/plain")).isInstanceOf(LinePromptSource.class);
    }

    @Test
    void throwsBadInputForUnsupportedContentType() {
        assertThatThrownBy(() -> registry.select("application/xml")).isInstanceOf(BadInputException.class);
    }
}
