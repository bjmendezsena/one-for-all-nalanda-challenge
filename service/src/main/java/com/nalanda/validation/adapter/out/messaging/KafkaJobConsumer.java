package com.nalanda.validation.adapter.out.messaging;

import com.nalanda.validation.application.ProcessValidationUseCase;
import com.nalanda.validation.config.KafkaConfig;
import com.nalanda.validation.domain.port.JobConsumer;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Wakes the processing use case up. Carries no business logic of its own — duplicate deliveries are
 * absorbed by the status machine, not here (see {@code docs/service/events.md} § 4).
 */
@Component
class KafkaJobConsumer implements JobConsumer {

    private static final String VALIDATION_REQUEST_ID_MDC_KEY = "validationRequestId";

    private final ProcessValidationUseCase processValidationUseCase;

    KafkaJobConsumer(ProcessValidationUseCase processValidationUseCase) {
        this.processValidationUseCase = processValidationUseCase;
    }

    @KafkaListener(topics = KafkaConfig.PROCESSING_REQUESTED_TOPIC, groupId = "validation-service")
    void onProcessingRequested(ProcessingRequestedEvent event) {
        onProcessingRequested(event.validationRequestId());
    }

    @Override
    public void onProcessingRequested(UUID validationRequestId) {
        MDC.put(VALIDATION_REQUEST_ID_MDC_KEY, validationRequestId.toString());
        try {
            processValidationUseCase.execute(validationRequestId);
        } finally {
            MDC.remove(VALIDATION_REQUEST_ID_MDC_KEY);
        }
    }
}
