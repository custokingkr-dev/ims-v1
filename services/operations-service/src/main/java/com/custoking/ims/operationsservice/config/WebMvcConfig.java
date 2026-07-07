package com.custoking.ims.operationsservice.config;

import com.custoking.ims.operationsservice.security.FirefightingModuleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final FirefightingModuleInterceptor firefightingModuleInterceptor;

    public WebMvcConfig(FirefightingModuleInterceptor firefightingModuleInterceptor) {
        this.firefightingModuleInterceptor = firefightingModuleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(firefightingModuleInterceptor)
                .addPathPatterns("/api/v1/ff/**", "/api/v1/workspace/firefighting");
    }
}
