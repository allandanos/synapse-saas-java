package dev.synapse.core.web;

import dev.synapse.core.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the resolved {@link TenantContext} into handler methods (resolution is idempotent per request). */
public class TenantContextArgumentResolver implements HandlerMethodArgumentResolver {

    private final TenantAccess tenants;

    public TenantContextArgumentResolver(TenantAccess tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TenantContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return tenants.resolve(request, Principals.current());
    }
}
