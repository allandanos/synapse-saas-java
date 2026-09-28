package dev.synapse.core.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.errors.DomainError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes a problem document straight to the servlet response (filters and entry points, outside MVC). */
@Component
public class ProblemWriter {

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, DomainError error) throws IOException {
        write(response, error.status(), ProblemDocument.of(error, request.getRequestURI(), requestId(request)));
    }

    public void write(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), body);
    }

    public static String requestId(HttpServletRequest request) {
        String fromContext = RequestContextHolder.requestId();
        return fromContext != null ? fromContext : request.getHeader("X-Request-Id");
    }
}
