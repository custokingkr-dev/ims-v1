package com.custoking.ims.config;

import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter(
            JwtService jwtService,
            AppUserDetailsService userDetailsService) {
        RequestLoggingFilter filter = new RequestLoggingFilter(jwtService, userDetailsService);
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(2);
        return registration;
    }
}
