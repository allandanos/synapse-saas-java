package dev.synapse.core.problem;

import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.errors.DomainError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaving MVC is a problem document ({@code application/json}):
 * domain errors keep their status/title/extras, request-parsing failures are
 * {@code validation_failed} (422) with {@code errors[]}, Spring's own 404/405/406
 * become {@code not_found}/{@code method_not_allowed}/…, and anything unhandled is a
 * 500 {@code internal_error} that never leaks the exception.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainError.class)
    public ResponseEntity<Map<String, Object>> domainError(DomainError error, HttpServletRequest request) {
        return json(error.status(), ProblemDocument.of(error, request.getRequestURI(), requestId(request)));
    }

    /** Unique-constraint violations the reference lets through as 500s; here they are a 409 {@code conflict}. */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicateKey(DuplicateKeyException error, HttpServletRequest request) {
        return json(409, ProblemDocument.build(409, "conflict", "A row with the same unique value already exists",
            request.getRequestURI(), requestId(request), Map.of()));
    }

    /** Method-level ({@code @Validated} proxy) violations — the MVC path normally reports these first. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraintViolation(ConstraintViolationException error, HttpServletRequest request) {
        List<ValidationErrors.FieldProblem> errors = error.getConstraintViolations().stream()
            .map(v -> {
                String code = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
                Map<String, Object> attrs = v.getConstraintDescriptor().getAttributes();
                return new ValidationErrors.FieldProblem(List.of("query", SnakeCase.of(leaf(v.getPropertyPath().toString()))),
                    ValidationErrors.message(code, attrs, v.getInvalidValue(), v.getMessage()),
                    ValidationErrors.type(code, attrs, v.getInvalidValue()));
            })
            .toList();
        return json(422, ValidationErrors.problem(errors, request.getRequestURI(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> argumentTypeMismatch(MethodArgumentTypeMismatchException error, HttpServletRequest request) {
        String location = ParameterLocations.of(error.getParameter());
        String name = SnakeCase.of(error.getName());
        String msg = error.getRequiredType() == java.util.UUID.class
            ? "Input should be a valid UUID"
            : "Input should be a valid " + (error.getRequiredType() == null ? "value" : error.getRequiredType().getSimpleName().toLowerCase());
        String type = error.getRequiredType() == java.util.UUID.class ? "uuid_parsing" : "type_error";
        return json(422, ValidationErrors.problem(List.of(new ValidationErrors.FieldProblem(List.of(location, name), msg, type)),
            request.getRequestURI(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unhandled(Exception error, HttpServletRequest request) {
        log.error("unhandled_exception path={} error={}", request.getRequestURI(), error.toString(), error);
        return json(500, ProblemDocument.build(500, "internal_error", "An unexpected error occurred.",
            null, requestId(request), Map.of()));
    }

    // ── Spring MVC exceptions (ResponseEntityExceptionHandler) ───────────────────

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Object target = ex.getBindingResult().getTarget();
        List<ValidationErrors.FieldProblem> errors = ValidationErrors.fromBindingErrors(
            ex.getBindingResult().getAllErrors(), "body", target == null ? null : target.getClass());
        return validation(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation(ValidationErrors.fromHandlerMethodValidation(ex), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation(ValidationErrors.fromUnreadableBody(ex.getCause()), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation(List.of(new ValidationErrors.FieldProblem(List.of("query", ex.getParameterName()), "Field required", "missing")), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "value" : ex.getPropertyName();
        return validation(List.of(new ValidationErrors.FieldProblem(List.of("query", name), "Input should be a valid value", "type_error")), request);
    }

    /** The reference (FastAPI) answers a non-JSON body with 422, not 415. */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation(List.of(new ValidationErrors.FieldProblem(List.of("body"),
            "Input should be a valid dictionary or object to extract fields from", "model_attributes_type")), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        String title = switch (status) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            case 503 -> "service_unavailable";
            default -> status >= 500 ? "internal_error" : "bad_request";
        };
        String detail = switch (status) {
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> ex.getMessage() == null ? ProblemDocument.humanTitle(title) : ex.getMessage();
        };
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (headers != null && headers.getAllow() != null && !headers.getAllow().isEmpty()) {
            builder.allow(headers.getAllow().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ProblemDocument.build(status, title, detail, path(request), requestId(request), Map.of()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ResponseEntity<Object> validation(List<ValidationErrors.FieldProblem> errors, WebRequest request) {
        return ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON)
            .body(ValidationErrors.problem(errors, path(request), requestId(request)));
    }

    private static ResponseEntity<Map<String, Object>> json(int status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    private static String requestId(WebRequest request) {
        return request instanceof ServletWebRequest swr ? requestId(swr.getRequest()) : RequestContextHolder.requestId();
    }

    private static String requestId(HttpServletRequest request) {
        String id = RequestContextHolder.requestId();
        return id != null ? id : request.getHeader("X-Request-Id");
    }

    private static String leaf(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }
}
