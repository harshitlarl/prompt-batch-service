package com.example.promptbatch.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;

public class BatchNotFoundExceptionMapper implements ExceptionMapper<BatchNotFoundException> {

    @Override
    public Response toResponse(BatchNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
