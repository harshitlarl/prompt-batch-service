package com.example.promptbatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.model.Prompt;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinePromptSourceTest {

    private final LinePromptSource source = new LinePromptSource();

    @Test
    void parsesOnePromptPerLineAndSkipsBlankLines() {
        var input = new ByteArrayInputStream(
                "first\n\nsecond\n  \nthird".getBytes(StandardCharsets.UTF_8));

        List<Prompt> prompts = source.parse("b1", input);

        assertThat(prompts).extracting(Prompt::text).containsExactly("first", "second", "third");
    }

    @Test
    void rejectsEmptyUpload() {
        var input = new ByteArrayInputStream("\n\n  \n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> source.parse("b1", input)).isInstanceOf(BadInputException.class);
    }

    @Test
    void supportsPlainTextAndOctetStream() {
        assertThat(source.supports("text/plain")).isTrue();
        assertThat(source.supports("application/octet-stream")).isTrue();
        assertThat(source.supports("application/json")).isFalse();
    }
}
