package dev.synapse.core.problem;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Translates Spring/Jakarta validation failures into the reference's
 * {@code validation_failed} problem: {@code errors[]} of {@code {loc, msg, type}}
 * with pydantic-style messages and types, and a {@code detail} naming the
 * first three offending fields.
 */
public final class ValidationErrors {

    public record FieldProblem(List<Object> loc, String msg, String type) {}

    private ValidationErrors() {}

    public static Map<String, Object> problem(List<FieldProblem> errors, String instance, String requestId) {
        Map<String, Object> doc = ProblemDocument.build(422, "validation_failed", detailFor(errors), instance, requestId, Map.of());
        doc.put("errors", errors.stream().map(ValidationErrors::entry).toList());
        return doc;
    }

    /** {@code {loc, msg, type}} in the reference's key order. */
    private static Map<String, Object> entry(FieldProblem e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("loc", e.loc());
        entry.put("msg", e.msg());
        entry.put("type", e.type());
        return entry;
    }

    public static String detailFor(List<FieldProblem> errors) {
        String fields = errors.stream()
            .limit(3)
            .map(e -> e.loc().stream().skip(1).map(String::valueOf).collect(Collectors.joining(".")))
            .map(s -> s.isEmpty() ? "body" : s)
            .collect(Collectors.joining(", "));
        return "Invalid request: " + (fields.isEmpty() ? "body" : fields);
    }

    /**
     * Body (bean) validation. Errors are reported in the request record's
     * declaration order (pydantic's order) with wire (snake_case) field names.
     */
    public static List<FieldProblem> fromBindingErrors(List<ObjectError> errors, String location, Class<?> target) {
        List<Object> order = declarationOrder(target);
        List<ObjectError> sorted = new ArrayList<>(errors);
        sorted.sort(Comparator.comparingInt(e -> e instanceof FieldError fe ? indexOf(order, fe.getField()) : Integer.MAX_VALUE));
        List<FieldProblem> out = new ArrayList<>();
        for (ObjectError error : sorted) {
            String field = error instanceof FieldError fe ? SnakeCase.of(fe.getField()) : null;
            Object rejected = error instanceof FieldError fe ? fe.getRejectedValue() : null;
            List<Object> loc = field == null ? List.of(location) : List.of(location, field);
            out.add(new FieldProblem(loc, message(error, rejected), type(error, rejected)));
        }
        return out;
    }

    private static List<Object> declarationOrder(Class<?> target) {
        if (target == null) {
            return List.of();
        }
        if (target.isRecord()) {
            return Arrays.stream(target.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toList());
        }
        return Arrays.stream(target.getDeclaredFields()).map(Field::getName).collect(Collectors.toList());
    }

    private static int indexOf(List<Object> order, String field) {
        int index = order.indexOf(field);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** Query/path parameter validation ({@code @Min} on {@code @RequestParam} etc.). */
    public static List<FieldProblem> fromHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<FieldProblem> out = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            String location = ParameterLocations.of(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                Object rejected = result.getArgument();
                String code = null;
                Map<String, Object> attrs = Map.of();
                try {
                    ConstraintViolation<?> violation = result.unwrap(error, ConstraintViolation.class);
                    code = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
                    attrs = violation.getConstraintDescriptor().getAttributes();
                } catch (RuntimeException ignored) {
                    String[] codes = error.getCodes();
                    code = codes == null || codes.length == 0 ? null : codes[codes.length - 1];
                }
                String fallback = String.valueOf(error.getDefaultMessage());
                out.add(new FieldProblem(List.of(location, name == null ? "param" : SnakeCase.of(name)),
                    message(code, attrs, rejected, fallback), type(code, attrs, rejected)));
            }
        });
        return out;
    }

    /** Unreadable JSON body: missing, malformed, or a field of the wrong type. */
    public static List<FieldProblem> fromUnreadableBody(Throwable cause) {
        if (cause instanceof InvalidFormatException ife) {
            List<Object> loc = pathOf(ife);
            if (ife.getTargetType() == UUID.class) {
                return List.of(new FieldProblem(loc, "Input should be a valid UUID", "uuid_parsing"));
            }
            return List.of(new FieldProblem(loc, "Input should be a valid " + simple(ife.getTargetType()), "value_error"));
        }
        if (cause instanceof MismatchedInputException mie) {
            List<Object> loc = pathOf(mie);
            return List.of(new FieldProblem(loc, "Input should be a valid " + simple(mie.getTargetType()), "type_error"));
        }
        if (cause instanceof JsonProcessingException) {
            return List.of(new FieldProblem(List.of("body"), "JSON decode error", "json_invalid"));
        }
        return List.of(new FieldProblem(List.of("body"), "Field required", "missing"));
    }

    private static List<Object> pathOf(JsonMappingException ex) {
        List<Object> loc = new ArrayList<>();
        loc.add("body");
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                loc.add(SnakeCase.of(ref.getFieldName()));
            } else if (ref.getIndex() >= 0) {
                loc.add(ref.getIndex());
            }
        }
        return loc;
    }

    private static String simple(Class<?> type) {
        if (type == null) {
            return "value";
        }
        if (List.class.isAssignableFrom(type)) {
            return "list";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "dictionary or object to extract fields from";
        }
        return type.getSimpleName().toLowerCase();
    }

    private static String message(ObjectError error, Object rejected) {
        return message(error.getCode(), attributes(error), rejected, String.valueOf(error.getDefaultMessage()));
    }

    private static String type(ObjectError error, Object rejected) {
        return type(error.getCode(), attributes(error), rejected);
    }

    /** pydantic-style message for a Jakarta constraint code ({@code NotNull}, {@code Size}, {@code Min}, …). */
    static String message(String rawCode, Map<String, Object> attrs, Object rejected, String fallback) {
        String code = Objects.requireNonNullElse(rawCode, "");
        return switch (code) {
            case "NotNull", "NotEmpty" -> "Field required";
            case "NotBlank" -> rejected == null ? "Field required" : "String should have at least 1 character";
            case "Size" -> sizeMessage(attrs, rejected);
            case "Pattern" -> "String should match pattern '" + attrs.get("regexp") + "'";
            case "Min" -> "Input should be greater than or equal to " + attrs.get("value");
            case "Max" -> "Input should be less than or equal to " + attrs.get("value");
            default -> fallback;
        };
    }

    static String type(String rawCode, Map<String, Object> attrs, Object rejected) {
        String code = Objects.requireNonNullElse(rawCode, "");
        return switch (code) {
            case "NotNull", "NotEmpty" -> "missing";
            case "NotBlank" -> rejected == null ? "missing" : "string_too_short";
            case "Size" -> length(rejected) < intAttr(attrs, "min", 0) ? "string_too_short" : "string_too_long";
            case "Pattern" -> "string_pattern_mismatch";
            case "Min" -> "greater_than_equal";
            case "Max" -> "less_than_equal";
            case "typeMismatch" -> "type_error";
            default -> "value_error";
        };
    }

    private static String sizeMessage(Map<String, Object> attrs, Object rejected) {
        int min = intAttr(attrs, "min", 0);
        int max = intAttr(attrs, "max", Integer.MAX_VALUE);
        if (length(rejected) < min) {
            return "String should have at least " + min + " character" + (min == 1 ? "" : "s");
        }
        return "String should have at most " + max + " character" + (max == 1 ? "" : "s");
    }

    private static int length(Object value) {
        return value instanceof CharSequence cs ? cs.length() : value instanceof List<?> l ? l.size() : 0;
    }

    private static int intAttr(Map<String, Object> attrs, String key, int fallback) {
        Object v = attrs.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static Map<String, Object> attributes(ObjectError error) {
        try {
            ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
            return violation.getConstraintDescriptor().getAttributes();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
