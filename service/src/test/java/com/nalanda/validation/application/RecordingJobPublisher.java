package com.nalanda.validation.application;

import com.nalanda.validation.domain.port.JobPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Hand-written fake that records what was published, instead of publishing it. */
class RecordingJobPublisher implements JobPublisher {

    private final List<UUID> publishedEvents = new ArrayList<>();

    @Override
    public void publishProcessingRequested(UUID validationRequestId) {
        publishedEvents.add(validationRequestId);
    }

    List<UUID> publishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
