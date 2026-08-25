const DEFAULT_BASE_URL = "http://localhost:8080";
const DEFAULT_API_KEY = "local-dev-api-key";

export interface ExampleConfig {
	baseUrl: string;
	apiKey: string;
}

export function resolveConfig(): ExampleConfig {
	return {
		baseUrl: process.env.BASE_URL ?? DEFAULT_BASE_URL,
		apiKey: process.env.API_KEY ?? DEFAULT_API_KEY,
	};
}
