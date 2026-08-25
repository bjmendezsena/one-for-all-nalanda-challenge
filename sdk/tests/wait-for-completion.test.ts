import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createClient } from "../src/client";

const PROCESSING = { requestId: "abc-123", status: "PROCESSING", result: null };
const COMPLETED = {
	requestId: "abc-123",
	status: "COMPLETED",
	result: { verdict: "PASS", fields: {}, reason: null },
};
const FAILED = { requestId: "abc-123", status: "FAILED", result: null };

function stubReads(...bodies: Array<unknown>): ReturnType<typeof vi.fn> {
	const mock = vi.fn();
	for (const body of bodies) {
		mock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => body });
	}
	mock.mockResolvedValue({ ok: true, status: 200, json: async () => PROCESSING });
	vi.stubGlobal("fetch", mock);
	return mock;
}

function newClient() {
	return createClient({ baseUrl: "https://api.local", apiKey: "local-dev-api-key" });
}

describe("waitForCompletion", () => {
	beforeEach(() => {
		vi.useFakeTimers();
		vi.unstubAllGlobals();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it("should_returnTheValidation_when_itReachesCompleted", async () => {
		stubReads(PROCESSING, PROCESSING, COMPLETED);

		const pending = newClient().waitForCompletion("abc-123");
		await vi.advanceTimersByTimeAsync(1_000);

		await expect(pending).resolves.toMatchObject({ status: "COMPLETED" });
	});

	it("should_returnTheValidation_when_itReachesFailed", async () => {
		stubReads(PROCESSING, FAILED);

		const pending = newClient().waitForCompletion("abc-123");
		await vi.advanceTimersByTimeAsync(1_000);

		await expect(pending).resolves.toMatchObject({ status: "FAILED" });
	});

	it("should_returnAfterASingleRead_when_theValidationIsAlreadyTerminal", async () => {
		const mock = stubReads(COMPLETED);

		await expect(newClient().waitForCompletion("abc-123")).resolves.toMatchObject({ status: "COMPLETED" });
		expect(mock).toHaveBeenCalledTimes(1);
	});

	it("should_doubleTheDelayUpToTheCeiling_when_theValidationStaysInProgress", async () => {
		const mock = stubReads();
		const controller = new AbortController();

		const settled = newClient()
			.waitForCompletion("abc-123", {
				initialDelayMs: 100,
				maxDelayMs: 250,
				timeoutMs: 60_000,
				signal: controller.signal,
			})
			.catch((error: unknown) => error);

		await vi.advanceTimersByTimeAsync(0);
		expect(mock).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(100);
		expect(mock).toHaveBeenCalledTimes(2);
		await vi.advanceTimersByTimeAsync(200);
		expect(mock).toHaveBeenCalledTimes(3);
		await vi.advanceTimersByTimeAsync(250);
		expect(mock).toHaveBeenCalledTimes(4);
		await vi.advanceTimersByTimeAsync(250);
		expect(mock).toHaveBeenCalledTimes(5);

		controller.abort();
		await vi.advanceTimersByTimeAsync(0);
		await expect(settled).resolves.toMatchObject({ name: "AbortError" });
	});

	it("should_throwATimeoutError_when_theBudgetIsSmallerThanASinglePause", async () => {
		stubReads();

		await expect(
			newClient().waitForCompletion("abc-123", { timeoutMs: 100, initialDelayMs: 250 }),
		).rejects.toThrow("waitForCompletion timed out after 100ms");
	});

	it("should_throwATimeoutError_when_theBudgetIsExhaustedAcrossSeveralReads", async () => {
		stubReads();

		const pending = newClient().waitForCompletion("abc-123", {
			timeoutMs: 500,
			initialDelayMs: 250,
			maxDelayMs: 250,
		});
		const settled = pending.catch((error: unknown) => error);
		await vi.advanceTimersByTimeAsync(1_000);

		await expect(settled).resolves.toMatchObject({ message: "waitForCompletion timed out after 500ms" });
	});

	it("should_rejectWithAbortError_when_theCallerCancelsAnInFlightWait", async () => {
		const mock = stubReads();
		const controller = new AbortController();

		const settled = newClient()
			.waitForCompletion("abc-123", { signal: controller.signal })
			.catch((error: unknown) => error);
		await vi.advanceTimersByTimeAsync(0);
		controller.abort();
		await vi.advanceTimersByTimeAsync(0);

		await expect(settled).resolves.toMatchObject({ name: "AbortError" });
		expect(mock).toHaveBeenCalledTimes(1);
	});

	it("should_rejectWithAbortError_when_theSignalIsAlreadyAborted", async () => {
		const mock = stubReads();

		const settled = newClient()
			.waitForCompletion("abc-123", { signal: AbortSignal.abort() })
			.catch((error: unknown) => error);
		await vi.advanceTimersByTimeAsync(0);

		await expect(settled).resolves.toMatchObject({ name: "AbortError" });
		expect(mock).toHaveBeenCalledTimes(1);
	});
});
