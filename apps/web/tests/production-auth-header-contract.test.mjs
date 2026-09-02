import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const clientSource = await readFile(
  new URL('../src/api/client.ts', import.meta.url),
  'utf8',
)

test('production bearer requests default to the Caddy authentication header', () => {
  assert.match(
    clientSource,
    /const BEARER_HEADER = import\.meta\.env\.VITE_BEARER_HEADER \?\? 'X-Hotel-AI-Authorization'/,
  )
})
