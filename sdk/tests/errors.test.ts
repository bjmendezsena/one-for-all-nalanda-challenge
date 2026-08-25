import { beforeEach, describe, expect, it, vi } from "vitest";
import { createClient } from "../src/client";
import { ValidationApiError } from "../src/errors";
import { request } from "../src/internal/http";

function stubResponse(response: Partial<Response> | Response): void {
	vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
}

describe("request", () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it("should_returnParsedBody_when_responseIsSuccessful", async () => {
		stubResponse(new Response(JSON.stringify({ requestId: "abc-123" }), { status: 200 }));

		await expect(request<{ requestId: string }>("https://api.local/x", {})).resolves.toEqual({
			requestId: "abc-123",
		});
	});

	it("should_returnUndefined_when_responseHasNoContent", async () => {
		stubResponse(new Response(null, { status: 204 }));

		await expect(request("https://api.local/x", {})).resolves.toBeUndefined();
	});

	it("should_returnUndefined_when_responseHasNoBody", async () => {
		stubResponse(new Response(null, { status: 200 }));

		await expect(request("https://api.local/x", {})).resolves.toBeUndefined();
	});

	it("should_throwValidationApiErrorWithProblemDetails_when_serverRejectsRequest", async () => {
		stubResponse({
			ok: false,
			status: 404,
			json: async () => ({ title: "Not Found", status: 404, detail: "Validation request not found" }),
		});

		const error = await request("https://api.local/x", {}).catch((thrown: unknown) => thrown);

		expect(error).toBeInstanceOf(ValidationApiError);
		expect(error).toMatchObject({
			name: "ValidationApiError",
			message: "Validation request not found",
			status: 404,
			body: { detail: "Validation request not found" },
		});
	});

	it("should_exposeFieldErrors_when_serverRejectsInvalidInput", async () => {
		stubResponse({
			ok: false,
			status: 400,
			json: async () => ({
				title: "Bad Request",
				status: 400,
				detail: "Validation failed",
				errors: [{ field: "filename", message: "must not be blank" }],
			}),
		});

		const error = (await request("https://api.local/x", {}).catch(
			(thrown: unknown) => thrown,
		)) as ValidationApiError;

		expect(error.status).toBe(400);
		expect(error.body?.errors).toEqual([{ field: "filename", message: "must not be blank" }]);
	});

	it("should_fallBackToStatusMessage_when_rejectionBodyIsNotJson", async () => {
		stubResponse({
			ok: false,
			status: 403,
			json: async () => {
				throw new SyntaxError("Unexpected token <");
			},
		});

		const error = (await request("https://api.local/x", {}).catch(
			(thrown: unknown) => thrown,
		)) as ValidationApiError;

		expect(error.message).toBe("Request failed with status 403");
		expect(error.status).toBe(403);
		expect(error.body).toBeUndefined();
	});
});

describe("ValidationApiError through the public client", () => {
	const client = createClient({ baseUrl: "https://api.local", apiKey: "local-dev-api-key" });

	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it("should_exposeNotFoundReason_when_requestIdIsUnknown", async () => {
		stubResponse({
			ok: false,
			status: 404,
			json: async () => ({ title: "Not Found", status: 404, detail: "Validation request 'missing' not found" }),
		});

		const error = (await client.getValidation("missing").catch((thrown: unknown) => thrown)) as ValidationApiError;

		expect(error.status).toBe(404);
		expect(error.message).toBe("Validation request 'missing' not found");
	});

	it("should_exposeFieldErrors_when_createIsRejectedAsInvalid", async () => {
		stubResponse({
			ok: false,
			status: 400,
			json: async () => ({
				status: 400,
				detail: "Validation failed",
				errors: [{ field: "contentType", message: "must not be blank" }],
			}),
		});

		const error = (await client
			.startValidation({ filename: "invoice.pdf", contentType: "" })
			.catch((thrown: unknown) => thrown)) as ValidationApiError;

		expect(error.status).toBe(400);
		expect(error.body?.errors).toEqual([{ field: "contentType", message: "must not be blank" }]);
	});

	it("should_exposeUnauthorizedReason_when_apiKeyIsRejected", async () => {
		stubResponse({
			ok: false,
			status: 401,
			json: async () => ({ status: 401, detail: "Invalid X-Api-Key header" }),
		});

		const error = (await client.getValidation("abc-123").catch((thrown: unknown) => thrown)) as ValidationApiError;

		expect(error.status).toBe(401);
		expect(error.message).toBe("Invalid X-Api-Key header");
	});

	it("should_exposeConflictReason_when_statusTransitionIsInvalid", async () => {
		vi.stubGlobal(
			"fetch",
			vi
				.fn()
				.mockResolvedValueOnce(new Response(null, { status: 200 }))
				.mockResolvedValueOnce({
					ok: false,
					status: 409,
					json: async () => ({ status: 409, detail: "Request abc-123 is in PROCESSING, expected QUEUED" }),
				}),
		);

		const error = (await client
			.upload({ requestId: "abc-123", uploadUrl: "https://minio.local/x" }, "bytes", "application/pdf")
			.catch((thrown: unknown) => thrown)) as ValidationApiError;

		expect(error.status).toBe(409);
		expect(error.message).toBe("Request abc-123 is in PROCESSING, expected QUEUED");
	});

	it("should_fallBackToStatusMessage_when_storageRejectsWithNonJsonBody", async () => {
		stubResponse({
			ok: false,
			status: 400,
			json: async () => {
				throw new SyntaxError("Unexpected token <");
			},
		});

		const error = (await client
			.upload({ requestId: "abc-123", uploadUrl: "https://minio.local/x" }, "bytes", "application/pdf")
			.catch((thrown: unknown) => thrown)) as ValidationApiError;

		expect(error).toBeInstanceOf(ValidationApiError);
		expect(error.message).toBe("Request failed with status 400");
		expect(error.body).toBeUndefined();
	});
});
