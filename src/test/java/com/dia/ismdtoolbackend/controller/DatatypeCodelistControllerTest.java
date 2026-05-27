package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DatatypeCodelistController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class DatatypeCodelistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void propertyDatatypes_returnsNineItemsInDeclaredOrder() throws Exception {
        mockMvc.perform(get("/api/codelist/property-datatypes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data[0].code").value("boolean"))
                .andExpect(jsonPath("$.data[0].label").value("Ano či ne"))
                .andExpect(jsonPath("$.data[1].code").value("date"))
                .andExpect(jsonPath("$.data[2].code").value("time"))
                .andExpect(jsonPath("$.data[3].code").value("dateTimeStamp"))
                .andExpect(jsonPath("$.data[4].code").value("integer"))
                .andExpect(jsonPath("$.data[5].code").value("double"))
                .andExpect(jsonPath("$.data[6].code").value("anyURI"))
                .andExpect(jsonPath("$.data[7].code").value("string"))
                .andExpect(jsonPath("$.data[7].label").value("Řetězec"))
                .andExpect(jsonPath("$.data[8].code").value("Literal"))
                .andExpect(jsonPath("$.data[8].label").value("Text"));
    }

    @Test
    void propertyDatatypes_doesNotLeakIriOnTheWire() throws Exception {
        mockMvc.perform(get("/api/codelist/property-datatypes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].iri").doesNotExist())
                .andExpect(jsonPath("$.data[0].iri").doesNotExist())
                .andExpect(jsonPath("$.data[8].iri").doesNotExist());
    }

    @Test
    void propertyDatatypes_allItemsCarryNonEmptyCodeAndLabel() throws Exception {
        mockMvc.perform(get("/api/codelist/property-datatypes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == null || @.code == '')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.label == null || @.label == '')]").isEmpty());
    }

    @Test
    void propertyDatatypes_accessibleWithoutAuth() throws Exception {
        // No @WithMockSecurityUser — endpoint must be anonymous (public chain).
        mockMvc.perform(get("/api/codelist/property-datatypes"))
                .andExpect(status().isOk());
    }
}
