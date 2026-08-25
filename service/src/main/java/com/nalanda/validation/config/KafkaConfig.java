package com.nalanda.validation.config;

import com.nalanda.validation.adapter.out.messaging.ProcessingRequestedEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * The topic constant and the JSON deserialization wiring that complements the {@code spring.kafka}
 * block of {@code application.yml} (see {@code docs/service/kafka.md}).
 */
@Configuration
public class KafkaConfig {

    public static final String PROCESSING_REQUESTED_TOPIC = "validation.processing-requested";

    @Bean
    ConsumerFactory<String, ProcessingRequestedEvent> processingRequestedConsumerFactory(
            KafkaProperties kafkaProperties) {
        var valueDeserializer = new ErrorHandlingDeserializer<>(new JsonDeserializer<>(ProcessingRequestedEvent.class));
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties(null);
        properties.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        return new DefaultKafkaConsumerFactory<>(properties, null, valueDeserializer);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ProcessingRequestedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, ProcessingRequestedEvent> processingRequestedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ProcessingRequestedEvent>();
        factory.setConsumerFactory(processingRequestedConsumerFactory);
        return factory;
    }
}
