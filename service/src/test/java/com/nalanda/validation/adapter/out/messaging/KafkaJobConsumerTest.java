package com.nalanda.validation.adapter.out.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nalanda.validation.application.ProcessValidationUseCase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KafkaJobConsumerTest {

    private final ProcessValidationUseCase processValidationUseCase = mock(ProcessValidationUseCase.class);

    private final KafkaJobConsumer consumer = new KafkaJobConsumer(processValidationUseCase);

    @Test
    void should_delegateTheRequestIdToTheUseCase_when_anEventIsReceived() {
        var validationRequestId = UUID.randomUUID();

        consumer.onProcessingRequested(new ProcessingRequestedEvent(validationRequestId));

        verify(processValidationUseCase).execute(validationRequestId);
    }
}
