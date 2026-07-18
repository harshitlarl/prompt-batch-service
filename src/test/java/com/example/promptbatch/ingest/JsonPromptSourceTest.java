package com.example.promptbatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.model.Prompt;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPromptSourceTest {

    private final JsonPromptSource source = new JsonPromptSource();

    @Test
    void parsesPromptsArrayIntoPromptsWithStableIds() {
        var input = new ByteArrayInputStream(
                "{\"prompts\":[\"hello\",\"world\"]}".getBytes(StandardCharsets.UTF_8));

        List<Prompt> prompts = source.parse("b1", input);

        assertThat(prompts).extracting(Prompt::text).containsExactly("hello", "world");
        assertThat(prompts).extracting(Prompt::id).containsExactly("b1-p0", "b1-p1");
    }

    @Test
    void rejectsPayloadWithoutPromptsField() {
        var input = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> source.parse("b1", input)).isInstanceOf(BadInputException.class);
    }

    @Test
    void rejectsMalformedJson() {
        var input = new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> source.parse("b1", input)).isInstanceOf(BadInputException.class);
    }

    @Test
    void supportsOnlyJsonContentType() {
        assertThat(source.supports("application/json")).isTrue();
        assertThat(source.supports("text/plain")).isFalse();
        assertThat(source.supports(null)).isFalse();
    }
}
