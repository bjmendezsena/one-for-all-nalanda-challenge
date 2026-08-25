import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const MAX_DOCUMENT_SIZE_IN_BYTES = 15 * 1024 * 1024;
const SAMPLE_DOCUMENT_FILENAME = "justificante.pdf";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

export const PDF_CONTENT_TYPE = "application/pdf";

export interface DocumentSet {
	valid: Uint8Array;
	empty: Uint8Array;
	oversized: Uint8Array;
}

export async function loadDocuments(): Promise<DocumentSet> {
	const valid = await readFile(resolve(packageRoot, SAMPLE_DOCUMENT_FILENAME));
	if (valid.byteLength === 0 || valid.byteLength > MAX_DOCUMENT_SIZE_IN_BYTES) {
		throw new Error(`${SAMPLE_DOCUMENT_FILENAME} must be a non-empty document of at most 15 MiB`);
	}
	return {
		valid: new Uint8Array(valid),
		empty: new Uint8Array(0),
		oversized: new Uint8Array(MAX_DOCUMENT_SIZE_IN_BYTES + 1).fill(0x41),
	};
}
