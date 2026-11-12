package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.StageView;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.WorkflowView;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract A5 for requests that reach no handler on a workflow path. Runs the full MVC configuration with a static resource
 * handler on {@code /**}, as Spring Boot registers one, so an unknown path fails in that resource handler
 * ({@code NoResourceFoundException}) exactly as in production rather than with no handler at all, and the service's own
 * advices are registered as beans the way the application finds them.
 */
class ProductOrderWorkflowRouteExceptionHandlerTest {
    private static final String ALLOWED_METHOD_MESSAGE = "This production workflow request does not accept that method";
    private static final String UNKNOWN_PATH_MESSAGE = "This production workflow request does not exist";

    ProductOrderWorkflowService workflows;
    GenericWebApplicationContext web;
    MockMvc mvc;

    @Configuration
    @EnableWebMvc
    static class BootLikeMvc implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
        }
    }

    /** Stands in for any other order endpoint, to show other paths keep Spring's default responses. */
    @RestController
    static class OtherOrderEndpoint {
        @PostMapping("/api/v1/catalog/orders/{id}/other-action")
        public Map<String, String> act(@PathVariable String id) {
            return Map.of("id", id);
        }
    }

    @BeforeEach
    void setup() {
        workflows = mock(ProductOrderWorkflowService.class);
        web = new GenericWebApplicationContext(new MockServletContext());
        new AnnotatedBeanDefinitionReader(web).register(BootLikeMvc.class);
        web.registerBean(ProductOrderWorkflowController.class, () -> new ProductOrderWorkflowController(workflows, "test-token"));
        web.registerBean(OtherOrderEndpoint.class, OtherOrderEndpoint::new);
        web.registerBean(CatalogOrderFormExceptionHandler.class, CatalogOrderFormExceptionHandler::new);
        web.registerBean(CatalogProductFormExceptionHandler.class, CatalogProductFormExceptionHandler::new);
        web.registerBean(ValidationExceptionHandler.class, () -> new ValidationExceptionHandler());
        web.registerBean(ProductOrderWorkflowRouteExceptionHandler.class, ProductOrderWorkflowRouteExceptionHandler::new);
        web.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(new TenantContextFilter()).build();
    }

    @AfterEach
    void close() {
        web.close();
    }

    @Test
    void aWrongMethodOnAWorkflowPathIsA405WithAMessageAndTheAllowedMethods() throws Exception {
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS/complete")))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.message").value(ALLOWED_METHOD_MESSAGE));
        mvc.perform(superadmin(delete("/api/v1/catalog/orders/CK-1/production-workflow")))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.message").value(ALLOWED_METHOD_MESSAGE));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS"))
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "PUT"))
                .andExpect(jsonPath("$.message").value(ALLOWED_METHOD_MESSAGE));
        verifyNoInteractions(workflows);
    }

    @Test
    void anUnknownWorkflowPathIsA404WithAMessage() throws Exception {
        // The WhatsApp links endpoint that school-core no longer serves (contract A4).
        mvc.perform(superadmin(put("/api/v1/catalog/orders/CK-1/production-workflow/whatsapp-groups/VENDOR"))
                        .contentType("application/json").content("{\"url\":\"https://chat.whatsapp.com/abcdefghijkl\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(UNKNOWN_PATH_MESSAGE));
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/production-workflow/whatsapp-groups")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(UNKNOWN_PATH_MESSAGE));
        verifyNoInteractions(workflows);
    }

    @Test
    void otherPathsKeepSpringsDefaultResponses() throws Exception {
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/other-action")))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
                .andExpect(content().string(""));
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/nothing-here")))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        // Not a workflow path, although it shares the prefix.
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/production-workflows")))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/other-action"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CK-1"));
    }

    @Test
    void handledWorkflowRequestsAreUnchanged() throws Exception {
        when(workflows.view("CK-1")).thenReturn(new WorkflowView("CK-1", "FLEX", "ACTIVE", "ORDER_DETAILS", 2, true, true,
                List.of(new StageView("ORDER_DETAILS", 1, "Order Details", "ACTIVE", Map.of(), null, null))));
        mvc.perform(superadmin(get("/api/v1/catalog/orders/CK-1/production-workflow")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        when(workflows.completeStage("CK-1", "ORDER_DETAILS", 1L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Workflow changed; refresh and try again"));
        mvc.perform(superadmin(post("/api/v1/catalog/orders/CK-1/production-workflow/stages/ORDER_DETAILS/complete"))
                        .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Workflow changed; refresh and try again"));
        mvc.perform(get("/api/v1/catalog/orders/CK-1/production-workflow"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("invalid catalog service token"));
    }

    private static MockHttpServletRequestBuilder superadmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Catalog-Service-Token", "test-token").header("X-Authenticated-Role", "SUPERADMIN")
                .header("X-Authenticated-User-Id", "1");
    }
}
