import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const clientSource = await readFile(
  new URL('../src/api/client.ts', import.meta.url),
  'utf8',
)
const pilotEnvironment = await readFile(
  new URL('../.env.pilot', import.meta.url),
  'utf8',
)

test('production bearer requests default to the Caddy authentication header', () => {
  assert.match(
    clientSource,
    /const BEARER_HEADER = import\.meta\.env\.VITE_BEARER_HEADER \?\? 'X-Hotel-AI-Authorization'/,
  )
})

test('Pilot builds fail closed to bearer authentication without demo fallback', () => {
  assert.match(pilotEnvironment, /^VITE_AUTH_MODE=bearer$/m)
  assert.match(pilotEnvironment, /^VITE_BEARER_HEADER=X-Hotel-AI-Authorization$/m)
  assert.match(pilotEnvironment, /^VITE_ENABLE_DEMO_FALLBACK=false$/m)
  assert.match(pilotEnvironment, /^VITE_ALLOW_DEMO_QUERY=false$/m)
  assert.match(pilotEnvironment, /^VITE_DEMO_ONLY=false$/m)
  assert.doesNotMatch(pilotEnvironment, /(^|=)(dev-header|server|true)$/m)
})
