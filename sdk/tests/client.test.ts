import { beforeEach, describe, expect, it, vi } from "vitest";
import { createClient } from "../src/client";
import { ValidationApiError } from "../src/errors";

const BASE_URL = "https://api.local";
const API_KEY = "local-dev-api-key";

function jsonResponse(status: number, body: unknown): Partial<Response> {
	return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function fetchMock(): ReturnType<typeof vi.fn> {
	return globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
}

function stubFetch(...responses: Array<Partial<Response>>): void {
	const mock = vi.fn();
	for (const response of responses) {
		mock.mockResolvedValueOnce(response);
	}
	vi.stubGlobal("fetch", mock);
}

function newClient(overrides: { baseUrl?: string; headers?: Record<string, string> } = {}) {
	return createClient({ baseUrl: overrides.baseUrl ?? BASE_URL, apiKey: API_KEY, headers: overrides.headers });
}

describe("startValidation", () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it("should_postFilenameAndContentType_when_startingAValidation", async () => {
		stubFetch(
			jsonResponse(201, { requestId: "abc-123", status: "PENDING_UPLOAD", uploadUrl: "https://minio.local/x" }),
		);

		const started = await newClient().startValidation({
			filename: "invoice.pdf",
			contentType: "application/pdf",
		});

		expect(started).toEqual({
			requestId: "abc-123",
			status: "PENDING_UPLOAD",
			uploadUrl: "https://minio.local/x",
		});
		const [url, init] = fetchMock().mock.calls[0];
		expect(url).toBe("https://api.local/api/v1/validations");
		expect(init.method).toBe("POST");
		expect(init.body).toBe(JSON.stringify({ filename: "invoice.pdf", contentType: "application/pdf" }));
		expect(init.headers["X-Api-Key"]).toBe(API_KEY);
		expect(init.headers["Content-Type"]).toBe("application/json");
	});

	it("should_sendIdempotencyKeyHeader_when_callerSuppliesOne", async () => {
		stubFetch(jsonResponse(201, { requestId: "abc-123", status: "PENDING_UPLOAD", uploadUrl: "u" }));

		await newClient().startValidation(
			{ filename: "invoice.pdf", contentType: "application/pdf" },
			{ idempotencyKey: "demo-key-1" },
		);

		expect(fetchMock().mock.calls[0][1].headers["Idempotency-Key"]).toBe("demo-key-1");
	});

	it("should_omitIdempotencyKeyHeader_when_callerSuppliesNone", async () => {
		stubFetch(jsonResponse(201, { requestId: "abc-123", status: "PENDING_UPLOAD", uploadUrl: "u" }));

		await newClient().startValidation({ filename: "invoice.pdf", contentType: "application/pdf" });

		expect(fetchMock().mock.calls[0][1].headers).not.toHaveProperty("Idempotency-Key");
	});

	it("should_mergeExtraHeaders_when_clientIsConfiguredWithThem", async () => {
		stubFetch(jsonResponse(201, { requestId: "abc-123", status: "PENDING_UPLOAD", uploadUrl: "u" }));

		await newClient({ headers: { "X-Correlation-Id": "corr-1" } }).startValidation({
			filename: "invoice.pdf",
			contentType: "application/pdf",
		});

		expect(fetchMock().mock.calls[0][1].headers["X-Correlation-Id"]).toBe("corr-1");
	});

	it("should_buildAWellFormedUrl_when_baseUrlHasATrailingSlash", async () => {
		stubFetch(jsonResponse(201, { requestId: "abc-123", status: "PENDING_UPLOAD", uploadUrl: "u" }));

		await newClient({ baseUrl: "https://api.local/" }).startValidation({
			filename: "invoice.pdf",
			contentType: "application/pdf",
		});

		expect(fetchMock().mock.calls[0][0]).toBe("https://api.local/api/v1/validations");
	});
});

describe("upload", () => {
	const target = { requestId: "abc-123", uploadUrl: "https://minio.local/bucket/abc-123/invoice.pdf?X-Amz=1" };

	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it("should_putBytesThenConfirm_when_uploadSucceeds", async () => {
		stubFetch(jsonResponse(200, {}), jsonResponse(202, { requestId: "abc-123", status: "QUEUED" }));

		const confirmed = await newClient().upload(target, "bytes", "application/pdf");

		expect(confirmed).toEqual({ requestId: "abc-123", status: "QUEUED" });
		const [putUrl, putInit] = fetchMock().mock.calls[0];
		expect(putUrl).toBe(target.uploadUrl);
		expect(putInit.method).toBe("PUT");
		expect(putInit.body).toBe("bytes");
		const [confirmUrl, confirmInit] = fetchMock().mock.calls[1];
		expect(confirmUrl).toBe("https://api.local/api/v1/validations/abc-123/confirm");
		expect(confirmInit.method).toBe("POST");
		expect(confirmInit.headers["X-Api-Key"]).toBe(API_KEY);
	});

	it("should_sendOnlyContentTypeToStorage_when_uploadingBytes", async () => {
		stubFetch(jsonResponse(200, {}), jsonResponse(202, { requestId: "abc-123", status: "QUEUED" }));

		await newClient({ headers: { "X-Correlation-Id": "corr-1" } }).upload(target, "bytes", "application/pdf");

		expect(fetchMock().mock.calls[0][1].headers).toEqual({ "Content-Type": "application/pdf" });
	});

	it("should_sendNoHeadersToStorage_when_contentTypeIsOmitted", async () => {
		stubFetch(jsonResponse(200, {}), jsonResponse(202, { requestId: "abc-123", status: "QUEUED" }));

		await newClient().upload(target, "bytes");

		expect(fetchMock().mock.calls[0][1].headers).toBeUndefined();
	});

	it("should_throwAndSkipConfirm_when_storageRejectsThePut", async () => {
		stubFetch({
			ok: false,
			status: 403,
			json: async () => {
				throw new SyntaxError("not json");
			},
		});

		await expect(newClient().upload(target, "bytes", "application/pdf")).rejects.toBeInstanceOf(
			ValidationApiError,
		);
		expect(fetchMock()).toHaveBeenCalledTimes(1);
	});
});

describe("getValidation", () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it("should_returnStatusWithoutResult_when_validationIsStillProcessing", async () => {
		stubFetch(jsonResponse(200, { requestId: "abc-123", status: "PROCESSING", result: null }));

		const current = await newClient().getValidation("abc-123");

		expect(current.status).toBe("PROCESSING");
		expect(current.result).toBeNull();
		expect(fetchMock().mock.calls[0][0]).toBe("https://api.local/api/v1/validations/abc-123");
	});

	it("should_returnResult_when_validationHasCompleted", async () => {
		stubFetch(
			jsonResponse(200, {
				requestId: "abc-123",
				status: "COMPLETED",
				result: { verdict: "FAIL", fields: { filename: "invoice.txt" }, reason: "unsupported content type" },
			}),
		);

		const current = await newClient().getValidation("abc-123");

		expect(current.result).toEqual({
			verdict: "FAIL",
			fields: { filename: "invoice.txt" },
			reason: "unsupported content type",
		});
	});
});
