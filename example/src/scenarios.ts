import { randomUUID } from "node:crypto";
import {
	createClient,
	ValidationApiError,
	type ConfirmUploadResponse,
	type StartValidationResponse,
	type UploadData,
	type ValidationRequestDto,
	type ValidationResult,
	type ValidationStatus,
	type Verdict,
} from "@nalanda/validation-sdk";
import { PDF_CONTENT_TYPE } from "./documents.js";
import { assertThat, type Scenario, type ScenarioContext } from "./runner.js";

const UNSUPPORTED_CONTENT_TYPE_REASON = "unsupported content type";
const EMPTY_FILE_REASON = "empty file";
const FILE_TOO_LARGE_REASON = "file too large";

const PNG_CONTENT_TYPE = "image/png";
const COMPLETED_STATUS: ValidationStatus = "COMPLETED";
const PENDING_UPLOAD_STATUS: ValidationStatus = "PENDING_UPLOAD";

const LARGE_UPLOAD_TIMEOUT_MS = 60_000;
const STORAGE_UNSIGNED_HEADERS_STATUS = 400;
const NOT_FOUND_STATUS = 404;
const INVALID_INPUT_STATUS = 400;
const UNAUTHORIZED_STATUS = 401;

export const scenarios: Scenario[] = [
	{
		id: "happy-path",
		title: "A valid PDF travels from registration to a passing verdict",
		covers: "startValidation + upload + waitForCompletion",
		expected: "COMPLETED with verdict PASS and no reason",
		run: async (context) => {
			const started = await context.client.startValidation(
				{ filename: "justificante.pdf", contentType: PDF_CONTENT_TYPE },
				{ idempotencyKey: context.newIdempotencyKey() },
			);
			assertThat(
				started.status === PENDING_UPLOAD_STATUS,
				`expected a new request in ${PENDING_UPLOAD_STATUS}, got ${started.status}`,
			);
			assertThat(started.uploadUrl.length > 0, "expected an upload URL");

			const confirmed = await context.client.upload(started, context.documents.valid, PDF_CONTENT_TYPE);
			assertThat(
				confirmed.status !== PENDING_UPLOAD_STATUS,
				`expected the request to leave ${PENDING_UPLOAD_STATUS} after confirming`,
			);

			const finished = await context.client.waitForCompletion(started.requestId);
			return expectVerdict(finished, "PASS", null);
		},
	},
	{
		id: "verdict-unsupported-type",
		title: "A document declared as PNG fails the content-type rule",
		covers: "business rule 5.1 — supported content type",
		expected: `COMPLETED with verdict FAIL, reason "${UNSUPPORTED_CONTENT_TYPE_REASON}"`,
		note: "status is COMPLETED — a FAIL verdict is a conclusive answer, not a failed request",
		run: async (context) => {
			const finished = await validate(context, {
				filename: "justificante.png",
				contentType: PNG_CONTENT_TYPE,
				data: "not really a png, but the rule only reads the declared type and the size",
			});
			return expectVerdict(finished, "FAIL", UNSUPPORTED_CONTENT_TYPE_REASON);
		},
	},
	{
		id: "verdict-empty",
		title: "A zero-byte document fails the size rule at its lower bound",
		covers: "business rule 5.2 — size strictly greater than zero",
		expected: `COMPLETED with verdict FAIL, reason "${EMPTY_FILE_REASON}"`,
		note: "status is COMPLETED — a FAIL verdict is a conclusive answer, not a failed request",
		run: async (context) => {
			const finished = await validate(context, {
				filename: "empty.pdf",
				contentType: PDF_CONTENT_TYPE,
				data: context.documents.empty,
			});
			return expectVerdict(finished, "FAIL", EMPTY_FILE_REASON);
		},
	},
	{
		id: "verdict-too-large",
		title: "A document one byte over the ceiling fails the size rule at its upper bound",
		covers: "business rule 5.2 — size at most 15 MiB",
		expected: `COMPLETED with verdict FAIL, reason "${FILE_TOO_LARGE_REASON}"`,
		note: "status is COMPLETED — a FAIL verdict is a conclusive answer, not a failed request",
		run: async (context) => {
			const finished = await validate(context, {
				filename: "oversized.pdf",
				contentType: PDF_CONTENT_TYPE,
				data: new Blob([context.documents.oversized]),
				timeoutMs: LARGE_UPLOAD_TIMEOUT_MS,
			});
			return expectVerdict(finished, "FAIL", FILE_TOO_LARGE_REASON);
		},
	},
	{
		id: "idempotent-create",
		title: "Replaying an idempotency key returns the original request",
		covers: "business rules § 6 — same key, different body",
		expected: "the same requestId, and the second body ignored",
		run: async (context) => {
			const idempotencyKey = context.newIdempotencyKey();
			const first = await context.client.startValidation(
				{ filename: "first.pdf", contentType: PDF_CONTENT_TYPE },
				{ idempotencyKey },
			);
			const replayed = await context.client.startValidation(
				{ filename: "completely-different.pdf", contentType: PDF_CONTENT_TYPE },
				{ idempotencyKey },
			);

			assertThat(
				replayed.requestId === first.requestId,
				`expected requestId ${first.requestId}, got ${replayed.requestId} — a new request was created`,
			);
			return `same requestId ${replayed.requestId}, status ${replayed.status}`;
		},
	},
	{
		id: "read-before-completion",
		title: "Reading a request before it is processed returns a status and no result",
		covers: "getValidation",
		expected: `${PENDING_UPLOAD_STATUS} with no result yet`,
		run: async (context) => {
			const started = await context.client.startValidation({
				filename: "unconfirmed.pdf",
				contentType: PDF_CONTENT_TYPE,
			});

			const current = await context.client.getValidation(started.requestId);
			assertThat(
				current.requestId === started.requestId,
				`expected requestId ${started.requestId}, got ${current.requestId}`,
			);
			assertThat(
				current.status === PENDING_UPLOAD_STATUS,
				`expected ${PENDING_UPLOAD_STATUS}, got ${current.status}`,
			);
			assertThat(!current.result, `expected no result yet, got ${JSON.stringify(current.result)}`);
			return `${current.status} with no result`;
		},
	},
	{
		id: "wait-timeout",
		title: "Waiting on a request that never gets confirmed exhausts its budget",
		covers: "waitForCompletion timeoutMs / initialDelayMs / maxDelayMs",
		expected: "a plain Error naming the timeout, not a ValidationApiError",
		run: async (context) => {
			const started = await context.client.startValidation({
				filename: "never-confirmed.pdf",
				contentType: PDF_CONTENT_TYPE,
			});

			const thrown = await capture(
				context.client.waitForCompletion(started.requestId, {
					timeoutMs: 750,
					initialDelayMs: 100,
					maxDelayMs: 200,
				}),
			);

			assertThat(thrown instanceof Error, `expected an Error, got ${String(thrown)}`);
			assertThat(
				!(thrown instanceof ValidationApiError),
				"expected a plain Error — a timeout is not an API rejection",
			);
			const message = (thrown as Error).message;
			assertThat(message.includes("timed out"), `expected the message to name the timeout, got "${message}"`);
			return `Error: ${message}`;
		},
	},
	{
		id: "wait-aborted",
		title: "Cancelling a wait in flight rejects promptly",
		covers: "waitForCompletion signal",
		expected: "a DOMException named AbortError",
		run: async (context) => {
			const started = await context.client.startValidation({
				filename: "aborted.pdf",
				contentType: PDF_CONTENT_TYPE,
			});

			const controller = new AbortController();
			const pending = context.client.waitForCompletion(started.requestId, {
				initialDelayMs: 5_000,
				signal: controller.signal,
			});
			setTimeout(() => controller.abort(), 150);

			const thrown = await capture(pending);
			assertThat(
				thrown instanceof DOMException,
				`expected a DOMException, got ${thrown?.constructor.name ?? String(thrown)}`,
			);
			const name = (thrown as DOMException).name;
			assertThat(name === "AbortError", `expected AbortError, got ${name}`);
			return `DOMException: ${name}`;
		},
	},
	{
		id: "read-unknown",
		title: "Reading a request that does not exist is a specific not-found error",
		covers: "ValidationApiError status and Problem Details body",
		expected: `${NOT_FOUND_STATUS} with a specific reason`,
		run: async (context) => {
			const unknownRequestId = randomUUID();
			const error = await expectApiError(
				context.client.getValidation(unknownRequestId),
				NOT_FOUND_STATUS,
			);

			assertThat(
				Boolean(error.body?.detail),
				"expected a Problem Details body carrying an actionable reason",
			);
			return `${error.status}: ${error.body?.detail}`;
		},
	},
	{
		id: "create-invalid-input",
		title: "A blank filename is rejected synchronously with per-field messages",
		covers: "ValidationApiError body.errors",
		expected: `${INVALID_INPUT_STATUS} naming the offending field`,
		run: async (context) => {
			const error = await expectApiError(
				context.client.startValidation({ filename: "", contentType: PDF_CONTENT_TYPE }),
				INVALID_INPUT_STATUS,
			);

			const fields: FieldError[] = error.body?.errors ?? [];
			assertThat(fields.length > 0, "expected at least one field error");
			assertThat(
				fields.some((fieldError) => fieldError.field === "filename"),
				`expected an error on "filename", got ${JSON.stringify(fields)}`,
			);
			return `${error.status}: ${error.body?.detail} — ${fields.map(describeField).join(", ")}`;
		},
	},
	{
		id: "confirm-replay",
		title: "Confirming an already-confirmed request is a safe no-op",
		covers: "business rules § 6 — confirm/upload replay",
		expected: "the second confirmation succeeds and returns the current status",
		run: async (context) => {
			const started = await context.client.startValidation({
				filename: "replayed.pdf",
				contentType: PDF_CONTENT_TYPE,
			});
			const first = await context.client.upload(started, context.documents.valid, PDF_CONTENT_TYPE);
			const second = await context.client.upload(started, context.documents.valid, PDF_CONTENT_TYPE);

			assertThat(
				second.requestId === first.requestId,
				`expected requestId ${first.requestId}, got ${second.requestId}`,
			);
			assertThat(
				second.status !== PENDING_UPLOAD_STATUS,
				`expected the request to stay out of ${PENDING_UPLOAD_STATUS}, got ${second.status}`,
			);
			return `first ${first.status}, replayed ${second.status}`;
		},
	},
	{
		id: "wrong-api-key",
		title: "An unrecognised API key is rejected before anything is created",
		covers: "ClientOptions.apiKey",
		expected: `${UNAUTHORIZED_STATUS} with a specific reason`,
		run: async (context) => {
			const unauthenticated = createClient({
				baseUrl: context.config.baseUrl,
				apiKey: "definitely-not-the-configured-key",
			});

			const error = await expectApiError(
				unauthenticated.startValidation({ filename: "denied.pdf", contentType: PDF_CONTENT_TYPE }),
				UNAUTHORIZED_STATUS,
			);
			assertThat(Boolean(error.body?.detail), "expected a Problem Details body carrying a reason");
			return `${error.status}: ${error.body?.detail}`;
		},
	},
	{
		id: "client-options",
		title: "A trailing slash in the base URL and extra headers are handled",
		covers: "ClientOptions.baseUrl normalisation and headers merging",
		expected: "the call succeeds against the same API",
		run: async (context) => {
			const customised = createClient({
				baseUrl: `${context.config.baseUrl}/`,
				apiKey: context.config.apiKey,
				headers: { "X-Correlation-Id": `example-${randomUUID()}` },
			});

			const started = await customised.startValidation({
				filename: "with-options.pdf",
				contentType: PDF_CONTENT_TYPE,
			});
			assertThat(
				started.status === PENDING_UPLOAD_STATUS,
				`expected ${PENDING_UPLOAD_STATUS}, got ${started.status}`,
			);
			return `created ${started.requestId} through a trailing-slash base URL with an extra header`;
		},
	},
	{
		id: "upload-without-content-type",
		title: "Omitting the content type is rejected by storage, and confirm is skipped",
		covers: "upload's optional contentType, and a rejection whose body is not Problem Details",
		expected: `${STORAGE_UNSIGNED_HEADERS_STATUS} with an unparsed body, request left in ${PENDING_UPLOAD_STATUS}`,
		note: "the presigned URL signs the content type, so omitting it makes storage reject the request",
		run: async (context) => {
			const started = await context.client.startValidation({
				filename: "unsigned.pdf",
				contentType: PDF_CONTENT_TYPE,
			});

			const error = await expectApiError(
				context.client.upload(started, context.documents.valid),
				STORAGE_UNSIGNED_HEADERS_STATUS,
			);
			assertThat(
				error.body === undefined,
				`expected no parsed body — storage answers XML, not Problem Details — got ${JSON.stringify(error.body)}`,
			);

			const current = await context.client.getValidation(started.requestId);
			assertThat(
				current.status === PENDING_UPLOAD_STATUS,
				`expected the confirm to be skipped, leaving ${PENDING_UPLOAD_STATUS}, got ${current.status}`,
			);
			return `${error.status} with no parsed body, request still ${current.status}`;
		},
	},
];

interface FieldError {
	field: string;
	message: string;
}

interface ValidationAttempt {
	filename: string;
	contentType: string;
	data: UploadData;
	timeoutMs?: number;
}

async function validate(context: ScenarioContext, attempt: ValidationAttempt): Promise<ValidationRequestDto> {
	const started: StartValidationResponse = await context.client.startValidation(
		{ filename: attempt.filename, contentType: attempt.contentType },
		{ idempotencyKey: context.newIdempotencyKey() },
	);
	const confirmed: ConfirmUploadResponse = await context.client.upload(
		started,
		attempt.data,
		attempt.contentType,
	);
	assertThat(
		confirmed.status !== PENDING_UPLOAD_STATUS,
		`expected the request to leave ${PENDING_UPLOAD_STATUS} after confirming`,
	);
	return context.client.waitForCompletion(started.requestId, { timeoutMs: attempt.timeoutMs });
}

function expectVerdict(finished: ValidationRequestDto, verdict: Verdict, reason: string | null): string {
	assertThat(
		finished.status === COMPLETED_STATUS,
		`expected status ${COMPLETED_STATUS}, got ${finished.status}`,
	);
	const result: ValidationResult | null | undefined = finished.result;
	assertThat(Boolean(result), "expected a result once the request completed");
	assertThat(result?.verdict === verdict, `expected verdict ${verdict}, got ${result?.verdict}`);
	assertThat(
		(result?.reason ?? null) === reason,
		`expected reason ${JSON.stringify(reason)}, got ${JSON.stringify(result?.reason ?? null)}`,
	);
	return `${finished.status} with verdict ${result?.verdict}, reason ${JSON.stringify(result?.reason ?? null)}`;
}

async function expectApiError(pending: Promise<unknown>, status: number): Promise<ValidationApiError> {
	const thrown = await capture(pending);
	assertThat(
		thrown instanceof ValidationApiError,
		`expected a ValidationApiError, got ${thrown === undefined ? "a resolved promise" : String(thrown)}`,
	);
	const error = thrown as ValidationApiError;
	assertThat(error.status === status, `expected status ${status}, got ${error.status}`);
	return error;
}

async function capture(pending: Promise<unknown>): Promise<unknown> {
	try {
		await pending;
		return undefined;
	} catch (error) {
		return error;
	}
}

function describeField(fieldError: FieldError): string {
	return `${fieldError.field} ${fieldError.message}`;
}
