package com.nalanda.validation.domain.port;

import java.util.UUID;

public interface JobPublisher {

    void publishProcessingRequested(UUID validationRequestId);
}
