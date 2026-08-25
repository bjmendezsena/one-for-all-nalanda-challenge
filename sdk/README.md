# @nalanda/validation-sdk

TypeScript client for the Nalanda document validation API.

The public surface, the error contract and the polling behaviour are defined in
[`docs/sdk/architecture.md`](../docs/sdk/architecture.md) and
[`docs/sdk/code_rules.md`](../docs/sdk/code_rules.md); the endpoint semantics it wraps live in
[`docs/business-rules.md`](../docs/business-rules.md).

## Install

```bash
npm install @nalanda/validation-sdk
```

## Usage

```typescript
import { createClient } from "@nalanda/validation-sdk";

const client = createClient({ baseUrl: "http://localhost:8080", apiKey: "..." });
```

The client's methods are added as each one is implemented.

## Development

```bash
npm install
npm test          # Vitest
npm run typecheck # tsc --noEmit
npm run build     # ESM + CJS + .d.ts into dist/
```
