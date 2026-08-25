import { request } from "./internal/http";
import type {
	ClientOptions,
	ConfirmUploadResponse,
	StartValidationCallOptions,
	StartValidationInput,
	StartValidationResponse,
	UploadData,
	UploadTarget,
	ValidationClient,
	ValidationRequestDto,
	WaitForCompletionOptions,
} from "./types";

const API_KEY_HEADER = "X-Api-Key";
const IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
const VALIDATIONS_PATH = "/api/v1/validations";
const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_INITIAL_DELAY_MS = 250;
const DEFAULT_MAX_DELAY_MS = 5_000;

export function createClient(options: ClientOptions): ValidationClient {
	const baseUrl = options.baseUrl.replace(/\/$/, "");
	const headers: Record<string, string> = {
		"Content-Type": "application/json",
		[API_KEY_HEADER]: options.apiKey,
		...options.headers,
	};

	return {
		startValidation: (input, callOptions) => startValidation(baseUrl, headers, input, callOptions),
		upload: (target, data, contentType) => upload(baseUrl, headers, target, data, contentType),
		getValidation: (requestId) => getValidation(baseUrl, headers, requestId),
		waitForCompletion: (requestId, waitOptions) => waitForCompletion(baseUrl, headers, requestId, waitOptions),
	};
}

function startValidation(
	baseUrl: string,
	headers: Record<string, string>,
	input: StartValidationInput,
	callOptions: StartValidationCallOptions = {},
): Promise<StartValidationResponse> {
	const requestHeaders = callOptions.idempotencyKey
		? { ...headers, [IDEMPOTENCY_KEY_HEADER]: callOptions.idempotencyKey }
		: headers;

	return request<StartValidationResponse>(`${baseUrl}${VALIDATIONS_PATH}`, {
		method: "POST",
		headers: requestHeaders,
		body: JSON.stringify(input),
	});
}

async function upload(
	baseUrl: string,
	headers: Record<string, string>,
	target: UploadTarget,
	data: UploadData,
	contentType?: string,
): Promise<ConfirmUploadResponse> {
	await request<undefined>(target.uploadUrl, {
		method: "PUT",
		headers: contentType ? { "Content-Type": contentType } : undefined,
		body: data as BodyInit,
	});

	return request<ConfirmUploadResponse>(`${baseUrl}${VALIDATIONS_PATH}/${target.requestId}/confirm`, {
		method: "POST",
		headers,
	});
}

function getValidation(
	baseUrl: string,
	headers: Record<string, string>,
	requestId: string,
): Promise<ValidationRequestDto> {
	return request<ValidationRequestDto>(`${baseUrl}${VALIDATIONS_PATH}/${requestId}`, {
		method: "GET",
		headers,
	});
}

async function waitForCompletion(
	baseUrl: string,
	headers: Record<string, string>,
	requestId: string,
	options: WaitForCompletionOptions = {},
): Promise<ValidationRequestDto> {
	const {
		timeoutMs = DEFAULT_TIMEOUT_MS,
		initialDelayMs = DEFAULT_INITIAL_DELAY_MS,
		maxDelayMs = DEFAULT_MAX_DELAY_MS,
		signal,
	} = options;
	const deadline = Date.now() + timeoutMs;
	let delay = initialDelayMs;

	while (true) {
		const current = await getValidation(baseUrl, headers, requestId);
		if (current.status === "COMPLETED" || current.status === "FAILED") {
			return current;
		}
		if (Date.now() + delay > deadline) {
			throw new Error(`waitForCompletion timed out after ${timeoutMs}ms`);
		}

		await sleep(delay, signal);
		delay = Math.min(delay * 2, maxDelayMs);
	}
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(new DOMException("Aborted", "AbortError"));
			return;
		}
		const timer = setTimeout(resolve, ms);
		signal?.addEventListener(
			"abort",
			() => {
				clearTimeout(timer);
				reject(new DOMException("Aborted", "AbortError"));
			},
			{ once: true },
		);
	});
}
