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

test('production authenticated requests default to the Caddy token header', () => {
  assert.match(
    clientSource,
    /const BEARER_HEADER = import\.meta\.env\.VITE_BEARER_HEADER \?\? 'X-Hotel-AI-Authorization'/,
  )
})

test('request assignment header uses the canonical business actor field', () => {
  assert.match(clientSource, /businessActorAssignmentId\?: string/)
  assert.match(clientSource, /identity\.businessActorAssignmentId/)
  assert.doesNotMatch(clientSource, /identity\.assignmentId/)
})

test('Pilot builds fail closed to token authentication without demo fallback', () => {
  assert.match(pilotEnvironment, /^VITE_AUTH_MODE=bearer$/m)
  assert.match(pilotEnvironment, /^VITE_BEARER_HEADER=X-Hotel-AI-Authorization$/m)
  assert.match(pilotEnvironment, /^VITE_ENABLE_DEMO_FALLBACK=false$/m)
  assert.match(pilotEnvironment, /^VITE_ALLOW_DEMO_QUERY=false$/m)
  assert.match(pilotEnvironment, /^VITE_DEMO_ONLY=false$/m)
  assert.match(pilotEnvironment, /^VITE_PRODUCT_VERSION=TECH-V0\.2-PILOT\.8$/m)
  assert.doesNotMatch(pilotEnvironment, /(^|=)(dev-header|server|true)$/m)
})
