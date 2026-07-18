package com.example.promptbatch.resources;

import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Simple liveness/sanity resource used to verify the server is up and reachable,
 * independent of the health-check endpoint on the admin port.
 */
@Path("/ping")
@Produces(MediaType.APPLICATION_JSON)
public class PingResource {

    @GET
    public Map<String, String> ping() {
        return Map.of("status", "ok", "message", "pong");
    }
}
