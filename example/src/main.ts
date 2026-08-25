import { randomUUID } from "node:crypto";
import { createClient, ValidationApiError } from "@nalanda/validation-sdk";
import { resolveConfig, type ExampleConfig } from "./config.js";
import { loadDocuments } from "./documents.js";
import { printHeader, printSummary, runScenarios, type ScenarioContext } from "./runner.js";
import { scenarios } from "./scenarios.js";

const config = resolveConfig();
const client = createClient({ baseUrl: config.baseUrl, apiKey: config.apiKey });

await preflight(config);

const context: ScenarioContext = {
	client,
	config,
	documents: await loadDocuments(),
	newIdempotencyKey: () => `example-${randomUUID()}`,
};

const startedAt = performance.now();
printHeader(scenarios.length, config.baseUrl);
const report = await runScenarios(scenarios, context);
printSummary(report, performance.now() - startedAt);

process.exit(report.failed === 0 ? 0 : 1);

async function preflight({ baseUrl }: ExampleConfig): Promise<void> {
	try {
		await client.getValidation(randomUUID());
	} catch (error) {
		if (error instanceof ValidationApiError) {
			return;
		}
		reportUnreachable(baseUrl, error);
	}
}

function reportUnreachable(baseUrl: string, error: unknown): never {
	console.error(`\nCannot reach ${baseUrl} — the example needs a running backend.\n`);
	console.error("Start the stack, in this order, from the repository root:\n");
	console.error("  npm run docker:up     # postgres, kafka, minio");
	console.error("  npm run dev:service   # the backend");
	console.error("  npm run build:sdk     # file:../sdk resolves to sdk/dist\n");
	console.error(`Reason: ${error instanceof Error ? error.message : String(error)}\n`);
	process.exit(1);
}
