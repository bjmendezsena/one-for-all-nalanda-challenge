package com.nalanda.validation.adapter.out.messaging;

import com.nalanda.validation.config.KafkaConfig;
import com.nalanda.validation.domain.port.JobPublisher;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaJobPublisher implements JobPublisher {

    private final KafkaTemplate<String, ProcessingRequestedEvent> kafkaTemplate;

    KafkaJobPublisher(KafkaTemplate<String, ProcessingRequestedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishProcessingRequested(UUID validationRequestId) {
        // Keyed by the request id so every message about the same request keeps its relative order
        // if the topic ever grows partitions (docs/service/kafka.md § 1).
        kafkaTemplate.send(
                KafkaConfig.PROCESSING_REQUESTED_TOPIC,
                validationRequestId.toString(),
                new ProcessingRequestedEvent(validationRequestId));
    }
}
