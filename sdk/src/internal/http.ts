import { ValidationApiError, type ProblemDetailsBody } from "../errors";

export async function request<T>(url: string, init: RequestInit): Promise<T> {
	const response = await fetch(url, init);
	if (!response.ok) {
		const problem = (await response.json().catch(() => undefined)) as ProblemDetailsBody | undefined;
		throw new ValidationApiError(response.status, problem);
	}
	const body = await response.text();
	return (body ? JSON.parse(body) : undefined) as T;
}
