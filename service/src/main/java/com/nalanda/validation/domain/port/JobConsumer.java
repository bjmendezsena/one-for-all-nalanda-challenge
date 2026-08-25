package com.nalanda.validation.domain.port;

import java.util.UUID;

/** Implemented by the Kafka adapter, which hands the id to the processing use case. */
public interface JobConsumer {

    void onProcessingRequested(UUID validationRequestId);
}
