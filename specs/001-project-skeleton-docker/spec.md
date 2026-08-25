# Feature Specification: Project skeleton and `docker/` local environment

**Feature Branch**: `001-project-skeleton-docker`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "Teniendo en cuenta la documentación, crea el esqueleto del proyecto incluido el docker-compose. Todo lo relacionado con docker debe convivir en una carpeta llamada docker. Dentro debe estar el docker-compose con los diferentes servicios definidos y sus volumenes para correr en local. Una vez hagas esto, documentalo dentro de docs en la architecture para que lo tenga en cuenta."

## Clarifications

### Session 2026-08-25

- Q: Should the `docker/` environment also containerize the backend itself, or only the local infrastructure it depends on? → A: Only the local infrastructure (database, broker, object storage); the backend runs outside the containers.
- Q: Which base package should the backend skeleton use? → A: `com.nalanda.validation` (the one already suggested in the backend architecture document).

## User Scenarios & Testing _(mandatory)_

### User Story 1 - Bring up the whole local environment with one command (Priority: P1)

A developer clones the repository and starts every local dependency the backend needs
(relational database, message broker, object storage) with a single command, from a single
folder that contains everything Docker-related. Data written to those dependencies survives
a stop/start cycle, so the developer does not lose local state between sessions.

**Why this priority**: Without the local environment nothing else in the repository can be
run or verified. It is the foundation the backend skeleton depends on.

**Independent Test**: Run the single start command from the `docker/` folder, observe the
three services reach a healthy state, write data, stop and restart the environment, and
confirm the data is still there.

**Acceptance Scenarios**:

1. **Given** a clean clone of the repository, **When** the developer runs the documented
   single start command, **Then** the database, the broker and the object storage all start
   and report healthy, with no manual pre-step.
2. **Given** the environment is running and data has been written, **When** the developer
   stops the environment and starts it again, **Then** the previously written data is still
   present.
3. **Given** the environment is running, **When** the developer inspects the repository root,
   **Then** every Docker-related file lives under the `docker/` folder and nowhere else.
4. **Given** a developer who wants different local ports or credentials, **When** they change
   the documented environment values, **Then** the environment starts with those values without
   editing the compose file itself.

---

### User Story 2 - Start coding against a skeleton that matches the documented architecture (Priority: P2)

A developer opens the repository and finds both deliverables already laid out exactly as the
architecture documents describe: the backend with its layered package structure and its
build/config entrypoints, and the client library with its source and configuration
entrypoints. The skeleton builds and its (empty) test run succeeds, so any later change starts
from a green baseline.

**Why this priority**: It removes the ambiguity of "where does this file go" for every later
feature, and it is the only way to prove the documented structure is actually buildable.

**Independent Test**: Run the backend build+test command and the client-library build+test
command on a clean clone; both complete successfully without any business code being present.

**Acceptance Scenarios**:

1. **Given** a clean clone, **When** the developer runs the backend build and test command,
   **Then** it completes successfully.
2. **Given** a clean clone, **When** the developer runs the client library build and test
   command, **Then** it completes successfully and produces the documented output shape.
3. **Given** the skeleton, **When** the developer compares its folder structure to the
   architecture documents, **Then** every layer/folder named in those documents exists and no
   layer or module that they do not define has been added.
4. **Given** the backend skeleton, **When** it is started with the local environment running,
   **Then** it connects to the database, the broker and the object storage using the values
   documented for the local environment.

---

### User Story 3 - Find the local environment described in the documentation (Priority: P3)

A developer (or an agent) reading the documentation learns where the Docker assets live, which
services exist, which ports and credentials they use locally, where their data is persisted,
and how to start and reset the environment — without having to read the compose file.

**Why this priority**: The documentation is the project's source of truth; an undocumented
change to the repository layout would leave the docs contradicting reality.

**Independent Test**: Read the architecture documentation and successfully start, use and reset
the environment following only what is written there.

**Acceptance Scenarios**:

1. **Given** the change is complete, **When** a reader opens the backend architecture document,
   **Then** it describes the `docker/` folder, each local service, its local endpoint, and its
   persisted data location.
2. **Given** the change is complete, **When** a reader looks for the repository layout and the
   "how to run" instructions, **Then** they reflect the `docker/` folder and no longer point to
   a compose file at the repository root.
3. **Given** the change is complete, **When** a reader checks the governing documents and the
   documentation index, **Then** no document still states that the compose file lives at the
   repository root.

---

### Edge Cases

- A required local port is already taken on the developer's machine → the developer can override
  it through the documented environment values without editing the compose file.
- The developer wants a clean slate → a documented reset action removes the persisted data of
  every service.
- The backend is started before the dependencies are ready → the environment exposes health
  signals so readiness can be waited on rather than guessed.
- The object storage starts empty → the bucket the backend expects must exist before the first
  upload, without a manual click-through step.
- Someone adds a Docker asset later (an extra service, an image definition) → it belongs under
  `docker/`, and the rule is stated in the documentation.

## Requirements _(mandatory)_

### Functional Requirements

- **FR-001**: The repository MUST contain a `docker/` folder holding every Docker-related asset;
  no Docker asset may live at the repository root or inside `service/` or `sdk/`.
- **FR-002**: The `docker/` folder MUST define exactly the three local dependencies the backend
  needs — a relational database, a message broker, and an S3-compatible object storage — and no
  other long-running service. A short-lived bootstrap step that provisions a resource one of those
  services needs (and then exits) is part of that service, not a fourth dependency. The backend
  itself is NOT containerized: it runs outside the environment and connects to it.
- **FR-003**: Each of those services MUST persist its data in a named volume so that state
  survives a stop/start cycle.
- **FR-004**: The whole environment MUST start with a single command and require no manual
  provisioning step before the backend can use it, including the existence of the bucket the
  backend uploads to.
- **FR-005**: Each service MUST expose a health signal that indicates when it is ready to accept
  connections.
- **FR-006**: Local endpoints, credentials and other environment values MUST be overridable
  without editing the compose file, and MUST ship with working, non-secret defaults.
- **FR-007**: The backend skeleton MUST exist under `service/` with exactly the layers, packages
  and resource locations named in the backend architecture document, rooted at the base package
  `com.nalanda.validation`, and MUST build and run its
  test task successfully while empty of business logic.
- **FR-008**: The client library skeleton MUST exist under `sdk/` with exactly the structure and
  entrypoints named in the SDK architecture document, and MUST build and run its test task
  successfully while empty of business logic.
- **FR-009**: The backend skeleton's local configuration MUST point at the endpoints and
  credentials that the `docker/` environment provides.
- **FR-010**: The backend architecture document MUST describe the `docker/` folder: its contents,
  each service's role, its local endpoint, its credentials source, and its persisted data.
- **FR-011**: Every document that currently states or implies that the compose file lives at the
  repository root MUST be updated in the same change, including the repository layout, the
  "how to run" instructions, the documentation index and the governing principles.
- **FR-012**: The documentation MUST state the rule that all future Docker assets live under
  `docker/`.
- **FR-013**: The change MUST NOT introduce any endpoint, business rule, event, status or
  dependency beyond what the existing documentation already defines.

### Key Entities

- **Local environment**: the set of three containerized dependencies (database, broker, object
  storage) plus their persisted volumes and their configuration values.
- **Backend skeleton**: the buildable, empty backend project laid out per the backend
  architecture document.
- **Client library skeleton**: the buildable, empty client library laid out per the SDK
  architecture document.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001**: A developer with a clean clone brings the full local environment up with one
  command and no manual step, in under 5 minutes on a first run.
- **SC-002**: Data written to any of the three services is still present after a full stop and
  restart of the environment, in 100% of attempts.
- **SC-003**: A developer with a clean clone gets a successful build and test run of both
  deliverables without writing any code.
- **SC-004**: 100% of the folders and layers named in the two architecture documents exist in the
  skeleton, and zero folders or modules not named there have been added.
- **SC-005**: Zero remaining statements anywhere in the documentation place the compose file at
  the repository root. References that name docker-compose as the chosen mechanism (rather than as
  a path) are unaffected and stay as written.
- **SC-006**: A reader can start, use and reset the environment using only the documentation,
  without opening the compose file.

## Assumptions

- The three local dependencies are exactly those already documented (relational database,
  message broker, S3-compatible object storage); no additional service is in scope.
- Moving the compose file from the repository root into `docker/` is an explicit, user-requested
  change to the layout recorded in the existing documentation and in the governing document; both
  are updated in this change rather than treated as a conflict.
- The skeleton contains no business logic: no endpoint, no domain rule, no event handling — only
  the structure, the build configuration and the local wiring.
- Local credentials are development-only defaults and are not secrets.
- Naming conventions already suggested by the documentation (base package `com.nalanda.validation`,
  topic, bucket) are adopted as-is rather than re-decided here.
