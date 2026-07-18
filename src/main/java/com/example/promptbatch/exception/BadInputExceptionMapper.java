package com.example.promptbatch.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;

public class BadInputExceptionMapper implements ExceptionMapper<BadInputException> {

    @Override
    public Response toResponse(BadInputException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
