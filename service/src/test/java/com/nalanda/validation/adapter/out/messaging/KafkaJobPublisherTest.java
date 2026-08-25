package com.nalanda.validation.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nalanda.validation.config.KafkaConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaJobPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ProcessingRequestedEvent> kafkaTemplate = mock(KafkaTemplate.class);

    private final KafkaJobPublisher publisher = new KafkaJobPublisher(kafkaTemplate);

    @Test
    void should_sendTheEventKeyedByTheRequestId_when_publishingProcessingRequested() {
        var validationRequestId = UUID.randomUUID();

        publisher.publishProcessingRequested(validationRequestId);

        var topic = ArgumentCaptor.forClass(String.class);
        var key = ArgumentCaptor.forClass(String.class);
        var payload = ArgumentCaptor.forClass(ProcessingRequestedEvent.class);
        verify(kafkaTemplate).send(topic.capture(), key.capture(), payload.capture());
        assertThat(topic.getValue()).isEqualTo(KafkaConfig.PROCESSING_REQUESTED_TOPIC);
        assertThat(key.getValue()).isEqualTo(validationRequestId.toString());
        assertThat(payload.getValue()).isEqualTo(new ProcessingRequestedEvent(validationRequestId));
    }
}
