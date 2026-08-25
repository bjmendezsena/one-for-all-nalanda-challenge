import { readFile } from "node:fs/promises";
import { createClient, ValidationApiError } from "../dist/index.js";

const baseUrl = process.env.BASE_URL ?? "http://localhost:8080";
const apiKey = process.env.API_KEY ?? "local-dev-api-key";
const filePath = process.argv[2] ?? "invoice.pdf";
const contentType = process.argv[3] ?? "application/pdf";

const client = createClient({ baseUrl, apiKey });

try {
	const started = await client.startValidation(
		{ filename: filePath.split("/").pop(), contentType },
		{ idempotencyKey: `example-${filePath}` },
	);
	console.log("started", started.requestId, started.status);

	const confirmed = await client.upload(started, await readFile(filePath), contentType);
	console.log("confirmed", confirmed.status);

	const finished = await client.waitForCompletion(started.requestId);
	console.log("finished", finished.status, finished.result?.verdict, finished.result?.reason);
} catch (error) {
	if (error instanceof ValidationApiError) {
		console.error(`API error ${error.status}:`, error.body?.detail ?? error.message);
		console.error(error.body?.errors ?? []);
		process.exitCode = 1;
	} else {
		throw error;
	}
}
