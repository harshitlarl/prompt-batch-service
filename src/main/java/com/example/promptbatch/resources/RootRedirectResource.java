package com.example.promptbatch.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

/**
 * Sends a bare {@code GET /} (e.g. someone opening the app's root domain directly) to the
 * dashboard at {@code /ui} instead of 404ing - the {@code AssetsBundle} in
 * {@link com.example.promptbatch.PromptBatchApplication} only serves {@code /ui/*}, it never
 * claims {@code /} itself.
 */
@Path("/")
public class RootRedirectResource {

    @GET
    public Response redirectToDashboard() {
        return Response.seeOther(UriBuilder.fromPath("/ui").build()).build();
    }
}
