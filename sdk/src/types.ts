export interface ClientOptions {
	baseUrl: string;
	apiKey: string;
	headers?: Record<string, string>;
}

export type ValidationStatus = "PENDING_UPLOAD" | "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED";

export type Verdict = "PASS" | "FAIL";

export interface ValidationResult {
	verdict: Verdict;
	fields: Record<string, unknown>;
	reason: string | null;
}

export interface ValidationRequestDto {
	requestId: string;
	status: ValidationStatus;
	result?: ValidationResult | null;
}

export interface StartValidationInput {
	filename: string;
	contentType: string;
}

export interface StartValidationCallOptions {
	idempotencyKey?: string;
}

export interface StartValidationResponse {
	requestId: string;
	status: ValidationStatus;
	uploadUrl: string;
}

export interface ConfirmUploadResponse {
	requestId: string;
	status: ValidationStatus;
}

export interface UploadTarget {
	requestId: string;
	uploadUrl: string;
}

export type UploadData = Blob | ArrayBuffer | ArrayBufferView | ReadableStream<Uint8Array> | string;

export interface WaitForCompletionOptions {
	timeoutMs?: number;
	initialDelayMs?: number;
	maxDelayMs?: number;
	signal?: AbortSignal;
}

export interface ValidationClient {
	startValidation(
		input: StartValidationInput,
		callOptions?: StartValidationCallOptions,
	): Promise<StartValidationResponse>;

	upload(target: UploadTarget, data: UploadData, contentType?: string): Promise<ConfirmUploadResponse>;

	getValidation(requestId: string): Promise<ValidationRequestDto>;

	waitForCompletion(requestId: string, options?: WaitForCompletionOptions): Promise<ValidationRequestDto>;
}
