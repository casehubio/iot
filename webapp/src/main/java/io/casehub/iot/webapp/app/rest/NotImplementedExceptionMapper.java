package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.webapp.NotImplementedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class NotImplementedExceptionMapper implements ExceptionMapper<NotImplementedException> {
    @Override
    public Response toResponse(NotImplementedException e) {
        return Response.status(501)
                .entity(Map.of("error", "Not implemented", "operation", e.operation()))
                .build();
    }
}
