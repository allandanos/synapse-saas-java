package dev.synapse.core.problem;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** pydantic's {@code loc[0]}: where a handler parameter came from. */
final class ParameterLocations {

    private ParameterLocations() {}

    static String of(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return "path";
        }
        if (parameter.hasParameterAnnotation(RequestHeader.class)) {
            return "header";
        }
        if (parameter.hasParameterAnnotation(RequestParam.class)) {
            return "query";
        }
        return "body";
    }
}
