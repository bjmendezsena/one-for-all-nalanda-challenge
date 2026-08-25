import type { ValidationClient } from "@nalanda/validation-sdk";
import type { ExampleConfig } from "./config.js";
import type { DocumentSet } from "./documents.js";

const NAME_COLUMN_WIDTH = 42;

export interface ScenarioContext {
	client: ValidationClient;
	config: ExampleConfig;
	documents: DocumentSet;
	newIdempotencyKey: () => string;
}

export interface Scenario {
	id: string;
	title: string;
	covers: string;
	expected: string;
	run: (context: ScenarioContext) => Promise<string>;
	note?: string;
}

export interface ScenarioOutcome {
	scenario: Scenario;
	passed: boolean;
	observed: string;
	durationMs: number;
}

export interface RunReport {
	outcomes: ScenarioOutcome[];
	passed: number;
	failed: number;
}

export function assertThat(condition: boolean, message: string): void {
	if (!condition) {
		throw new Error(message);
	}
}

export async function runScenarios(scenarios: Scenario[], context: ScenarioContext): Promise<RunReport> {
	const outcomes: ScenarioOutcome[] = [];

	for (const [index, scenario] of scenarios.entries()) {
		const outcome = await runOne(scenario, context);
		outcomes.push(outcome);
		printOutcome(outcome, index + 1, scenarios.length);
	}

	const failed = outcomes.filter((outcome) => !outcome.passed).length;
	return { outcomes, passed: outcomes.length - failed, failed };
}

async function runOne(scenario: Scenario, context: ScenarioContext): Promise<ScenarioOutcome> {
	const startedAt = performance.now();
	try {
		const observed = await scenario.run(context);
		return { scenario, passed: true, observed, durationMs: performance.now() - startedAt };
	} catch (error) {
		return {
			scenario,
			passed: false,
			observed: describe(error),
			durationMs: performance.now() - startedAt,
		};
	}
}

function describe(error: unknown): string {
	return error instanceof Error ? `${error.name}: ${error.message}` : String(error);
}

export function printHeader(scenarioCount: number, baseUrl: string): void {
	console.log(`\nNalanda SDK example — ${scenarioCount} scenarios against ${baseUrl}\n`);
}

function printOutcome(outcome: ScenarioOutcome, position: number, total: number): void {
	const { scenario } = outcome;
	const counter = `[${String(position).padStart(String(total).length, " ")}/${total}]`;
	const dots = ".".repeat(Math.max(3, NAME_COLUMN_WIDTH - scenario.id.length));
	const verdict = outcome.passed ? "PASS" : "FAIL";
	const seconds = `${(outcome.durationMs / 1000).toFixed(1)}s`;

	console.log(`  ${counter} ${scenario.id} ${dots} ${verdict}   ${seconds}`);
	console.log(`         covers   ${scenario.covers}`);
	console.log(`         expected ${scenario.expected}`);
	console.log(`         observed ${outcome.observed}`);
	if (scenario.note) {
		console.log(`         note     ${scenario.note}`);
	}
	console.log("");
}

export function printSummary(report: RunReport, totalMs: number): void {
	const total = report.outcomes.length;
	console.log(
		`${total} scenarios: ${report.passed} passed, ${report.failed} failed  (${(totalMs / 1000).toFixed(1)}s)\n`,
	);
	if (report.failed > 0) {
		console.log("Failed scenarios:");
		for (const outcome of report.outcomes.filter((candidate) => !candidate.passed)) {
			console.log(`  - ${outcome.scenario.id}: expected ${outcome.scenario.expected}`);
			console.log(`    ${outcome.observed}`);
		}
		console.log("");
	}
}
