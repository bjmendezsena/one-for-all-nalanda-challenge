# Code rules

Status: living document. This file lives under `docs/service/` — it is specific to the backend (`service/`). The SDK has its own equivalent, `docs/sdk/code_rules.md`. This file describes the implementation-level conventions for `service/` — how each layer is actually written, with examples. It assumes familiarity with `docs/service/architecture.md` (the hexagonal layers and package structure) and `docs/business-rules.md` (the domain rules being implemented, shared with the SDK). It does not repeat the reasoning behind each choice — the rationale and discarded alternatives live in `docs/design-trade-offs.md`, indexed by the same section names used here.

`<base-package>` below stands for the project's base package (suggested: `com.nalanda.validation`, see `docs/service/architecture.md` § 4.2).

## 1. Domain models

Two kinds of objects live in `domain/model`:

- **Entities** — have identity and a lifecycle. In this slice, only `ValidationRequest`. Modeled as a mutable class whose state can only change through methods that represent business transitions. No public setters. Every transition validates the current state and throws a domain exception (§5) if the transition is invalid.
- **Value objects** — no identity, defined entirely by their data. `DocumentMetadata` and `ValidationResult`. Modeled as immutable Java `record`s.

```java
// domain/model/ValidationRequest.java
public class ValidationRequest {

    private final UUID id;
    private DocumentMetadata document; // not final: processing records the discovered size (see below)
    private ValidationStatus status;
    private ValidationResult result;

    private ValidationRequest(UUID id, DocumentMetadata document, ValidationStatus status) {
        this.id = id;
        this.document = document;
        this.status = status;
    }

    public static ValidationRequest create(DocumentMetadata document) {
        return new ValidationRequest(UUID.randomUUID(), document, ValidationStatus.PENDING_UPLOAD);
    }

    public void confirmUpload() {
        if (status != ValidationStatus.PENDING_UPLOAD) {
            // Idempotent no-op for a repeated confirm — see docs/business-rules.md § 6
            return;
        }
        this.status = ValidationStatus.QUEUED;
    }

    public void startProcessing() {
        requireStatus(ValidationStatus.QUEUED);
        this.status = ValidationStatus.PROCESSING;
    }

    public void complete(ValidationResult result) {
        requireStatus(ValidationStatus.PROCESSING);
        this.status = ValidationStatus.COMPLETED;
        this.result = result;
    }

    public void fail() {
        requireStatus(ValidationStatus.PROCESSING);
        this.status = ValidationStatus.FAILED;
    }

    public void recordDiscoveredSize(long sizeInBytes) {
        // The size is only knowable during processing — docs/service/upload-flow.md § 2.4.
        // A transition method of the aggregate, not a public setter: the no-anemic-model rule holds.
        this.document = document.withSizeInBytes(sizeInBytes);
    }

    private void requireStatus(ValidationStatus expected) {
        if (status != expected) {
            throw new InvalidStatusTransitionException(id, status, expected);
        }
    }

    public UUID getId() { return id; }
    public ValidationStatus getStatus() { return status; }
    public DocumentMetadata getDocument() { return document; }
    public ValidationResult getResult() { return result; }
}
```

```java
// domain/model/DocumentMetadata.java
public record DocumentMetadata(String filename, String contentType, long sizeInBytes, String storageKey) {
    public DocumentMetadata withSizeInBytes(long discoveredSizeInBytes) {
        return new DocumentMetadata(filename, contentType, discoveredSizeInBytes, storageKey);
    }
}

// domain/model/ValidationResult.java
public record ValidationResult(Verdict verdict, Map<String, Object> fields, String reason) {
    public enum Verdict { PASS, FAIL }
}
```

## 2. Ports

Port interfaces live in `domain/port`. Naming follows the role of the port, not a uniform suffix — `Repository`/`Publisher`/`Consumer` are self-explanatory; `Port` is used only where the role wouldn't otherwise be clear (`DocumentStoragePort`).

A "not found" lookup never returns `Optional` — it's never a valid outcome for these lookups, so the adapter throws the domain exception directly, and the port's return type is the plain domain object.

```java
// domain/port/ValidationRequestRepository.java
public interface ValidationRequestRepository {
    ValidationRequest save(ValidationRequest request);
    ValidationRequest save(ValidationRequest request, String idempotencyKey); // the key belongs to the record, not to the entity
    ValidationRequest findById(UUID id); // throws ValidationRequestNotFoundException if missing
    Optional<ValidationRequest> findByIdempotencyKey(String idempotencyKey); // a real optional business case
}

// domain/port/JobPublisher.java
public interface JobPublisher {
    void publishProcessingRequested(UUID validationRequestId);
}

// domain/port/JobConsumer.java — implemented by the Kafka adapter, invoked by the application layer
public interface JobConsumer {
    void onProcessingRequested(UUID validationRequestId);
}

// domain/port/DocumentStoragePort.java
public interface DocumentStoragePort {
    PresignedUpload createPresignedUpload(String storageKey, String contentType);
    long sizeOf(String storageKey);
}
```

Note `findByIdempotencyKey` still returns `Optional`: unlike `findById`, "no request with this key yet" *is* a valid, expected outcome (it just means this is the first time this key is seen) — the two cases are different in kind, not just in how the code happens to be written.

## 3. Adapters

Class names are prefixed with the backing technology, so the technology is readable from the class name alone. Mapping between domain and persistence/messaging models is done by hand — small, explicit `toDomain()`/`toEntity()` methods, no MapStruct.

```java
// adapter/out/persistence/JpaValidationRequestRepository.java
@Repository
class JpaValidationRequestRepository implements ValidationRequestRepository {

    private final SpringDataValidationRequestRepository springDataRepository;

    JpaValidationRequestRepository(SpringDataValidationRequestRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public ValidationRequest save(ValidationRequest request) {
        var entity = ValidationRequestMapper.toEntity(request);
        return ValidationRequestMapper.toDomain(springDataRepository.save(entity));
    }

    @Override
    public ValidationRequest findById(UUID id) {
        return springDataRepository.findById(id)
            .map(ValidationRequestMapper::toDomain)
            .orElseThrow(() -> new ValidationRequestNotFoundException(id));
    }
}

// adapter/out/persistence/ValidationRequestMapper.java
class ValidationRequestMapper {
    static ValidationRequestEntity toEntity(ValidationRequest domain) { /* explicit field-by-field mapping */ }
    static ValidationRequest toDomain(ValidationRequestEntity entity) { /* explicit field-by-field mapping */ }
}
```

```java
// adapter/out/messaging/KafkaJobPublisher.java
@Component
class KafkaJobPublisher implements JobPublisher {
    private final KafkaTemplate<String, ProcessingRequestedEvent> kafkaTemplate;

    @Override
    public void publishProcessingRequested(UUID validationRequestId) {
        kafkaTemplate.send("validation.processing-requested", new ProcessingRequestedEvent(validationRequestId));
    }
}

// adapter/out/storage/S3DocumentStorageAdapter.java
@Component
class S3DocumentStorageAdapter implements DocumentStoragePort {
    private final S3Presigner presigner;
    private final S3Client s3Client;
    // ...
}
```

## 4. Application layer (use cases)

One class per use case. Each class has a single public method, depends only on the ports it actually needs (constructor injection), and has no knowledge of HTTP, Kafka message formats, or JPA.

```java
// application/CreateValidationUseCase.java
@Service
public class CreateValidationUseCase {

    private final ValidationRequestRepository repository;
    private final DocumentStoragePort storage;

    public CreateValidationUseCase(ValidationRequestRepository repository, DocumentStoragePort storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public CreateValidationResult execute(CreateValidationCommand command, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return CreateValidationResult.from(existing.get());
            }
        }
        var document = new DocumentMetadata(command.filename(), command.contentType(), 0, generateStorageKey());
        var request = ValidationRequest.create(document);
        var presignedUpload = storage.createPresignedUpload(document.storageKey(), document.contentType());
        repository.save(request, idempotencyKey);
        return new CreateValidationResult(request.getId(), presignedUpload.url());
    }
}
```

```java
// application/ConfirmUploadUseCase.java
@Service
public class ConfirmUploadUseCase {
    private final ValidationRequestRepository repository;
    private final JobPublisher jobPublisher;

    public void execute(UUID requestId) {
        var request = repository.findById(requestId);
        request.confirmUpload(); // no-op if already confirmed — see § 1 and docs/business-rules.md § 6
        repository.save(request);
        if (request.getStatus() == ValidationStatus.QUEUED) {
            jobPublisher.publishProcessingRequested(request.getId());
        }
    }
}
```

## 5. Error handling

`domain` stays free of any framework dependency: domain exceptions are plain `RuntimeException` subclasses. Translation to `ResponseStatusException`/`ProblemDetail` happens only at the boundary, in `adapter/in/web`.

```java
// domain/model/ValidationRequestNotFoundException.java
public class ValidationRequestNotFoundException extends RuntimeException {
    private final UUID requestId;
    public ValidationRequestNotFoundException(UUID requestId) {
        super("Validation request not found: " + requestId);
        this.requestId = requestId;
    }
    public UUID getRequestId() { return requestId; }
}

// domain/model/InvalidStatusTransitionException.java
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(UUID requestId, ValidationStatus actual, ValidationStatus expected) {
        super("Request %s is in status %s, expected %s".formatted(requestId, actual, expected));
    }
}
```

```java
// adapter/in/web/ApiExceptionHandler.java
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ValidationRequestNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ValidationRequestNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        var errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList();
        problem.setProperty("errors", errors); // the errors[] extension decided in README § Design trade-offs
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
```

Every new domain exception gets exactly one `@ExceptionHandler` here mapping it to its HTTP status — this is the single place that translation happens.

## 6. Controllers

**Explicitly accepted exception to the domain-purity rule** (see `docs/design-trade-offs.md § Controllers`): `domain/model` classes are annotated directly with Jackson and `jakarta.validation` and returned/accepted as-is by controllers — there are no separate request/response DTOs in this codebase.

```java
// domain/model/ValidationRequest.java (excerpt, annotated for the web boundary — see accepted trade-off above)
public class ValidationRequest {
    @JsonProperty("requestId")
    public UUID getId() { return id; }

    @JsonProperty("status")
    public ValidationStatus getStatus() { return status; }
    // ...
}
```

```java
// adapter/in/web/ValidationController.java
@RestController
@RequestMapping("/api/v1/validations")
class ValidationController {

    private final CreateValidationUseCase createValidationUseCase;
    private final ConfirmUploadUseCase confirmUploadUseCase;
    private final GetValidationUseCase getValidationUseCase;

    @PostMapping
    ResponseEntity<CreateValidationResult> create(
            @Valid @RequestBody CreateValidationCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = createValidationUseCase.execute(command, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{requestId}/confirm")
    ResponseEntity<ConfirmUploadResult> confirm(@PathVariable UUID requestId) {
        return ResponseEntity.accepted().body(confirmUploadUseCase.execute(requestId));
    }

    @GetMapping("/{requestId}")
    ValidationRequest get(@PathVariable UUID requestId) {
        return getValidationUseCase.execute(requestId);
    }
}
```

`CreateValidationCommand`, `CreateValidationResult` and `ConfirmUploadResult` are small `record`s that happen to live in `application` (they're the use case's input/output), reused directly as the request/response shape — not a separate DTO layer.

## 7. Testing conventions

| Layer | Test type | Tooling |
|---|---|---|
| `domain` | Plain unit tests | JUnit only, no Spring context |
| `application` | Use-case tests against hand-written fakes of their ports | JUnit + fakes (e.g. `InMemoryValidationRequestRepository`), no Mockito |
| `adapter/in/web` | Controller slice tests | `@WebMvcTest`, use cases mocked |
| `adapter/out/persistence` | Repository slice tests | `@DataJpaTest` against H2 (see README § Design trade-offs → Integration testing strategy) |
| `adapter/out/messaging`, `adapter/out/storage` | Unit tests with mocks | JUnit + Mockito |

Test method naming: `should_<expectedBehavior>_when_<condition>()`.

```java
// domain — pure unit test, no Spring
class ValidationRequestTest {
    @Test
    void should_throwException_when_startingProcessingBeforeConfirm() {
        var request = ValidationRequest.create(someDocument());
        assertThrows(InvalidStatusTransitionException.class, request::startProcessing);
    }
}

// application — hand-written fake, no Mockito
class ConfirmUploadUseCaseTest {
    @Test
    void should_notRepublishEvent_when_confirmingAlreadyQueuedRequest() {
        var repository = new InMemoryValidationRequestRepository();
        var publisher = new RecordingJobPublisher();
        var request = ValidationRequest.create(someDocument());
        request.confirmUpload();
        repository.save(request);

        new ConfirmUploadUseCase(repository, publisher).execute(request.getId());

        assertThat(publisher.publishedEvents()).isEmpty();
    }
}
```

## 8. General naming conventions (summary)

Beyond the per-layer naming already shown above, the whole codebase follows standard Java naming conventions — this is not a per-project invention, it's the baseline every reviewer will expect:

- **Booleans** (fields, local variables, and no-arg accessor methods) are named as a predicate, prefixed with `is`, `has`, `can`, or `should` — never a bare noun or adjective. Examples: `isValid`, `hasIdempotencyKey`, `canTransitionTo(nextStatus)`, `shouldRetry`. Boolean getters follow Java's own convention of `isX()` rather than `getX()` — this also keeps them compatible with Jackson's default boolean (de)serialization if a boolean ever needs to cross the JSON boundary.
- **Methods** are named as verbs or verb phrases describing the action taken (`confirmUpload()`, `startProcessing()`, `findByIdempotencyKey(...)`), never as nouns.
- **Classes and interfaces** are `PascalCase`; **methods, fields, and local variables** are `camelCase`; **constants** (`static final`) are `UPPER_SNAKE_CASE` (e.g. `MAX_DOCUMENT_SIZE_BYTES`); **packages** are all-lowercase, no underscores.
- Names spell words out — no abbreviations beyond the ones already established as domain vocabulary (`id`, `dto`). `req`, `val`, `mgr`, and similar are not used.
- Magic numbers and strings that encode a business rule (e.g. the 15MB size threshold, `"application/pdf"`) are named constants next to the rule that uses them (see `docs/business-rules.md` § 5), not inlined as literals.

This is on top of the input-validation and error-body requirements the assignment states directly: inputs are validated with `jakarta.validation` annotations (§ 6), and every error response is a Problem Details body (§ 5) — both non-negotiable per the assignment's "Quality bar for the API".

| Element | Convention | Example |
|---|---|---|
| Port interface | Role-based name, `Port` suffix only when the role isn't self-evident | `ValidationRequestRepository`, `DocumentStoragePort` |
| Adapter class | Technology prefix + port name | `JpaValidationRequestRepository`, `KafkaJobPublisher`, `S3DocumentStorageAdapter` |
| Use case class | `<Verb><Noun>UseCase` | `CreateValidationUseCase`, `ConfirmUploadUseCase` |
| Domain exception | `<Condition>Exception`, plain `RuntimeException` | `ValidationRequestNotFoundException` |
| Boolean field / method | `is`/`has`/`can`/`should` prefix | `isValid`, `hasIdempotencyKey`, `canTransitionTo(...)` |
| Constant | `UPPER_SNAKE_CASE` | `MAX_DOCUMENT_SIZE_BYTES` |
| Test class | `<ClassUnderTest>Test` | `ValidationRequestTest`, `ConfirmUploadUseCaseTest` |
| Test method | `should_<expectedBehavior>_when_<condition>()` | `should_throwException_when_confirmingAlreadyQueuedRequest()` |
| Test fake | `In-Memory<Port>` / `Recording<Port>` | `InMemoryValidationRequestRepository`, `RecordingJobPublisher` |

## 9. Restrictions for AI coding assistants

This section exists so that any AI coding assistant working on `service/` — regardless of which one — stays inside the decisions already made in this document and in `docs/design-trade-offs.md`, instead of silently substituting a "more idiomatic" alternative. These are hard restrictions, not suggestions:

**Architecture and domain**
- `domain/model` and `domain/port` never import Spring, JPA, Kafka, or the AWS SDK. Zero framework annotations in `domain/`.
- Domain exceptions extend plain `RuntimeException` — never `ResponseStatusException` or any other Spring exception type (§ 5).
- No anemic domain model: `ValidationRequest` changes state only through its own methods (`confirmUpload()`, `startProcessing()`, `complete()`, `fail()`) — never through public setters manipulated from outside (§ 1).
- JPA entities live in `adapter/out/persistence`; `domain/model` is never annotated with `@Entity`.
- Controllers may expose `domain/model` directly (the one accepted, documented exception — § 6), but never a type from `adapter/out/*` or a raw JPA entity.
- Every error path produces a `ProblemDetail` via the central `@RestControllerAdvice` (§ 5) — never a raw stack trace, and never a second, ad-hoc error shape invented per controller.
- Kafka, JPA, and S3 calls never happen directly from `application` or `domain` — only through the ports (`JobPublisher`, `JobConsumer`, `DocumentStoragePort`, `ValidationRequestRepository`) (§ 2, § 3).
- `ConfirmUploadUseCase` never calls `DocumentStoragePort` — confirm does no storage I/O, by design (see `docs/design-trade-offs.md § Document upload flow` and `docs/service/upload-flow.md`).

**Testing**
- `application`-layer tests use hand-written fakes (`InMemory...`, `Recording...`) — Mockito is not used there; Mockito is reserved for `adapter/out/messaging` and `adapter/out/storage` tests only (§ 7).
- Testcontainers is not introduced — the agreed strategy is H2 + hand-written fakes/mocks (see `docs/design-trade-offs.md § Integration testing strategy`).
- Test method names always follow `should_<expectedBehavior>_when_<condition>()` — never `test1()`, `testCreate()`, or similar (§ 7, § 8).

**Cross-cutting**
- Any new dependency, library, or piece of infrastructure is flagged as a question to the human before being added — never introduced silently because it's "commonly used" or "more idiomatic".
- Any deviation from a decision already documented in `docs/design-trade-offs.md` or in this file is raised as an explicit question — never silently substituted.
- All documentation stays in English.
- Existing entries in `docs/**/*.md` are not rewritten to "clean them up" — only additive edits or changes explicitly requested by the human are made.
