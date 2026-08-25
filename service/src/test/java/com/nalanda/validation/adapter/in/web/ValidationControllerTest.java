package com.nalanda.validation.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nalanda.validation.application.ConfirmUploadResult;
import com.nalanda.validation.application.ConfirmUploadUseCase;
import com.nalanda.validation.application.CreateValidationCommand;
import com.nalanda.validation.application.CreateValidationResult;
import com.nalanda.validation.application.CreateValidationUseCase;
import com.nalanda.validation.application.GetValidationUseCase;
import com.nalanda.validation.config.WebFilterConfig;
import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationRequestNotFoundException;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValidationController.class)
@Import(WebFilterConfig.class)
class ValidationControllerTest {

    private static final String API_KEY = "local-dev-api-key";
    private static final UUID REQUEST_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateValidationUseCase createValidationUseCase;

    @MockitoBean
    private ConfirmUploadUseCase confirmUploadUseCase;

    @MockitoBean
    private GetValidationUseCase getValidationUseCase;

    @Test
    void should_returnCreatedWithTheUploadInstructions_when_creatingAValidation() throws Exception {
        when(createValidationUseCase.execute(any(CreateValidationCommand.class), eq("demo-key-1")))
                .thenReturn(new CreateValidationResult(
                        REQUEST_ID, ValidationStatus.PENDING_UPLOAD, "https://storage.test/key?X-Amz-Signature=abc"));

        mockMvc.perform(post("/api/v1/validations")
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", "demo-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"invoice.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.test/key?X-Amz-Signature=abc"));
    }

    @Test
    void should_returnBadRequestWithTheOffendingFields_when_creatingWithABlankFilename() throws Exception {
        mockMvc.perform(post("/api/v1/validations")
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("filename"));
    }

    @Test
    void should_returnBadRequestWithTheOffendingFields_when_creatingWithoutAContentType() throws Exception {
        mockMvc.perform(post("/api/v1/validations")
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"invoice.pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contentType"));
    }

    @Test
    void should_returnUnauthorized_when_creatingWithoutTheApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"invoice.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void should_returnAcceptedWithTheCurrentStatus_when_confirmingAnUpload() throws Exception {
        when(confirmUploadUseCase.execute(REQUEST_ID))
                .thenReturn(new ConfirmUploadResult(REQUEST_ID, ValidationStatus.QUEUED));

        mockMvc.perform(post("/api/v1/validations/{requestId}/confirm", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void should_returnNotFound_when_confirmingAnUnknownRequest() throws Exception {
        when(confirmUploadUseCase.execute(REQUEST_ID)).thenThrow(new ValidationRequestNotFoundException(REQUEST_ID));

        mockMvc.perform(post("/api/v1/validations/{requestId}/confirm", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void should_returnUnauthorized_when_confirmingWithoutTheApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/validations/{requestId}/confirm", REQUEST_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_returnTheStatusWithoutAResult_when_readingARequestBeforeCompletion() throws Exception {
        when(getValidationUseCase.execute(REQUEST_ID)).thenReturn(processingRequest());

        mockMvc.perform(get("/api/v1/validations/{requestId}", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.document").doesNotExist());
    }

    @Test
    void should_returnTheFullResult_when_readingACompletedRequest() throws Exception {
        var request = processingRequest();
        request.complete(new ValidationResult(ValidationResult.Verdict.PASS, Map.of("filename", "invoice.pdf"), null));
        when(getValidationUseCase.execute(REQUEST_ID)).thenReturn(request);

        mockMvc.perform(get("/api/v1/validations/{requestId}", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.verdict").value("PASS"))
                .andExpect(jsonPath("$.result.fields.filename").value("invoice.pdf"))
                .andExpect(jsonPath("$.result.reason").doesNotExist())
                .andExpect(jsonPath("$.document").doesNotExist());
    }

    @Test
    void should_returnNotFound_when_readingAnUnknownRequest() throws Exception {
        when(getValidationUseCase.execute(REQUEST_ID)).thenThrow(new ValidationRequestNotFoundException(REQUEST_ID));

        mockMvc.perform(get("/api/v1/validations/{requestId}", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_returnUnauthorized_when_readingWithoutTheApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/validations/{requestId}", REQUEST_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void should_returnUnauthorized_when_theApiKeyIsWrong() throws Exception {
        mockMvc.perform(get("/api/v1/validations/{requestId}", REQUEST_ID)
                        .header(ApiKeyFilter.API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    private static ValidationRequest processingRequest() {
        var request = ValidationRequest.restore(
                REQUEST_ID,
                new DocumentMetadata("invoice.pdf", "application/pdf", 0, "key/invoice.pdf"),
                ValidationStatus.QUEUED,
                null);
        request.startProcessing();
        return request;
    }
}
