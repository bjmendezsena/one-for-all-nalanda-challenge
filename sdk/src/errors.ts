export interface ProblemDetailsBody {
	type?: string;
	title?: string;
	status?: number;
	detail?: string;
	errors?: Array<{ field: string; message: string }>;
}

export class ValidationApiError extends Error {
	readonly status: number;
	readonly body?: ProblemDetailsBody;

	constructor(status: number, body?: ProblemDetailsBody) {
		super(body?.detail ?? `Request failed with status ${status}`);
		this.name = "ValidationApiError";
		this.status = status;
		this.body = body;
	}
}
