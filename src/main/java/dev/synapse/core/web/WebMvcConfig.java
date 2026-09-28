package dev.synapse.core.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantAccess tenants;
    private final AccessInterceptor accessInterceptor;

    public WebMvcConfig(TenantAccess tenants, AccessInterceptor accessInterceptor) {
        this.tenants = tenants;
        this.accessInterceptor = accessInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PrincipalArgumentResolver());
        resolvers.add(new TenantContextArgumentResolver(tenants));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessInterceptor).addPathPatterns("/v1/**");
    }
}
