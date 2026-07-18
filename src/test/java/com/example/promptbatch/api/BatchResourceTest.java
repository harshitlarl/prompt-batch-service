package com.example.promptbatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.promptbatch.api.dto.BatchProgressResponse;
import com.example.promptbatch.api.dto.BatchResultsResponse;
import com.example.promptbatch.api.dto.CreateBatchRequest;
import com.example.promptbatch.api.dto.CreateBatchResponse;
import com.example.promptbatch.ingest.JsonPromptSource;
import com.example.promptbatch.ingest.LinePromptSource;
import com.example.promptbatch.ingest.PromptSourceRegistry;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.service.BatchService;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Thin-resource tests: HTTP status codes + delegation, no business logic (LLD.md §5.8). */
class BatchResourceTest {

    private final PromptSourceRegistry registry =
            new PromptSourceRegistry(List.of(new JsonPromptSource(), new LinePromptSource()));

    @Test
    void createReturns202WithBatchIdAndTotal() {
        BatchService service = mock(BatchService.class);
        Batch batch = new Batch("b-123", 2);
        when(service.submit(any())).thenReturn(batch);
        BatchResource resource = new BatchResource(service, registry);

        CreateBatchRequest request = new CreateBatchRequest();
        request.setPrompts(List.of("a", "b"));

        Response response = resource.create(request);

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getEntity()).isEqualTo(new CreateBatchResponse("b-123", 2));
    }

    @Test
    void uploadSelectsSourceByContentTypeAndReturns202() {
        BatchService service = mock(BatchService.class);
        Batch batch = new Batch("b-456", 3);
        when(service.submitFromSource(any(), any())).thenReturn(batch);
        BatchResource resource = new BatchResource(service, registry);

        var body = new ByteArrayInputStream("a\nb\nc".getBytes(StandardCharsets.UTF_8));
        Response response = resource.upload(body, "text/plain");

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getEntity()).isEqualTo(new CreateBatchResponse("b-456", 3));
    }

    @Test
    void progressReturnsLiveCounters() {
        BatchService service = mock(BatchService.class);
        Batch batch = new Batch("b-1", 4);
        batch.status(BatchStatus.PROCESSING);
        batch.recordResult(PromptResult.success("p0", "ok", 1));
        when(service.get("b-1")).thenReturn(batch);
        BatchResource resource = new BatchResource(service, registry);

        BatchProgressResponse progress = resource.progress("b-1");

        assertThat(progress.batchId()).isEqualTo("b-1");
        assertThat(progress.total()).isEqualTo(4);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.status()).isEqualTo("PROCESSING");
    }

    @Test
    void resultsReturns409WhileStillRunning() {
        BatchService service = mock(BatchService.class);
        Batch batch = new Batch("b-1", 4);
        batch.status(BatchStatus.PROCESSING);
        when(service.get("b-1")).thenReturn(batch);
        BatchResource resource = new BatchResource(service, registry);

        Response response = resource.results("b-1");

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void resultsReturns200WithAggregatedOutcomesWhenCompleted() {
        BatchService service = mock(BatchService.class);
        Batch batch = new Batch("b-1", 1);
        batch.status(BatchStatus.PROCESSING);
        batch.recordResult(PromptResult.success("p0", "ok", 1));
        when(service.get("b-1")).thenReturn(batch);
        BatchResource resource = new BatchResource(service, registry);

        Response response = resource.results("b-1");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(BatchResultsResponse.from(batch));
    }
}
