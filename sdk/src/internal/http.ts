import { ValidationApiError, type ProblemDetailsBody } from "../errors";

const NO_CONTENT_STATUS = 204;

export async function request<T>(url: string, init: RequestInit): Promise<T> {
	const response = await fetch(url, init);
	if (!response.ok) {
		const problem = (await response.json().catch(() => undefined)) as ProblemDetailsBody | undefined;
		throw new ValidationApiError(response.status, problem);
	}
	return response.status === NO_CONTENT_STATUS ? (undefined as T) : ((await response.json()) as T);
}
