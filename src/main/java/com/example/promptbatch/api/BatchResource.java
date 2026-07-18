package com.example.promptbatch.api;

import com.example.promptbatch.api.dto.BatchProgressResponse;
import com.example.promptbatch.api.dto.BatchResultsResponse;
import com.example.promptbatch.api.dto.CreateBatchRequest;
import com.example.promptbatch.api.dto.CreateBatchResponse;
import com.example.promptbatch.ingest.PromptSourceRegistry;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.service.BatchService;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.List;

/**
 * Thin HTTP edge: validate input, delegate to {@link BatchService}, map to a DTO. No business
 * logic lives here (LLD.md §5.8).
 */
@Path("/batches")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Batches", description = "Submit prompt batches, track progress, fetch results")
public class BatchResource {

    private final BatchService service;
    private final PromptSourceRegistry ingestRegistry;

    public BatchResource(BatchService service, PromptSourceRegistry ingestRegistry) {
        this.service = service;
        this.ingestRegistry = ingestRegistry;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Submit a batch of prompts (JSON)",
            description = "Accepts a JSON array of prompts, kicks off async processing, and returns "
                    + "immediately with a batchId to poll for progress/results.")
    @ApiResponse(responseCode = "202", description = "Accepted",
            content = @Content(schema = @Schema(implementation = CreateBatchResponse.class)))
    @ApiResponse(responseCode = "422", description = "Validation error (e.g. empty prompts list)")
    @Timed(name = "create")
    @ExceptionMetered(name = "create.exceptions")
    public Response create(@Valid CreateBatchRequest request) {
        Batch batch = service.submit(request.getPrompts());
        return Response.status(Response.Status.ACCEPTED)
                .entity(new CreateBatchResponse(batch.id(), batch.total()))
                .build();
    }

    @POST
    @Path("/upload")
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON})
    @Operation(
            summary = "Submit a batch of prompts (file upload)",
            description = "Accepts a raw request body: either newline-delimited prompts "
                    + "(Content-Type: text/plain) or a JSON body (Content-Type: application/json).")
    @ApiResponse(responseCode = "202", description = "Accepted",
            content = @Content(schema = @Schema(implementation = CreateBatchResponse.class)))
    @ApiResponse(responseCode = "415", description = "Unsupported content type")
    @Timed(name = "upload")
    @ExceptionMetered(name = "upload.exceptions")
    public Response upload(InputStream body, @HeaderParam("Content-Type") String contentType) {
        var source = ingestRegistry.select(contentType);
        Batch batch = service.submitFromSource(source, body);
        return Response.status(Response.Status.ACCEPTED)
                .entity(new CreateBatchResponse(batch.id(), batch.total()))
                .build();
    }

    @GET
    @Operation(
            summary = "List all batches",
            description = "Returns a progress summary for every batch known to the service, "
                    + "most recently submitted first. Powers the batch status dashboard.")
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(schema = @Schema(implementation = BatchProgressResponse.class)))
    @Timed(name = "list")
    @ExceptionMetered(name = "list.exceptions")
    public List<BatchProgressResponse> list() {
        return service.listAll().stream().map(BatchProgressResponse::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get live batch progress", description = "Returns the current status/counters for a batch.")
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(schema = @Schema(implementation = BatchProgressResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown batchId")
    @Timed(name = "progress")
    @ExceptionMetered(name = "progress.exceptions")
    public BatchProgressResponse progress(
            @Parameter(description = "batchId returned by the submit endpoints") @PathParam("id") String id) {
        return BatchProgressResponse.from(service.get(id));
    }

    @GET
    @Path("/{id}/results")
    @Operation(
            summary = "Get final aggregated results",
            description = "Returns per-prompt results once the batch reaches COMPLETED; otherwise returns "
                    + "409 with the current progress snapshot.")
    @ApiResponse(responseCode = "200", description = "Batch completed",
            content = @Content(schema = @Schema(implementation = BatchResultsResponse.class)))
    @ApiResponse(responseCode = "409", description = "Batch still running",
            content = @Content(schema = @Schema(implementation = BatchProgressResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown batchId")
    @Timed(name = "results")
    @ExceptionMetered(name = "results.exceptions")
    public Response results(
            @Parameter(description = "batchId returned by the submit endpoints") @PathParam("id") String id) {
        Batch batch = service.get(id);
        if (batch.status() != BatchStatus.COMPLETED) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(BatchProgressResponse.from(batch))
                    .build();
        }
        return Response.ok(BatchResultsResponse.from(batch)).build();
    }
}
