import assert from 'node:assert/strict'
import { test } from 'node:test'
import { authorizedFetch, loadAccessToken, saveAccessToken, uploadAudio } from '../src/api.ts'

test('access keys are scoped to server origin and expired keys produce a useful error', async () => {
  const saved = new Map<string, string>()
  Object.defineProperty(globalThis, 'location', { configurable: true, value: { origin: 'http://localhost:3000' } })
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: (key: string) => saved.get(key) ?? null,
    setItem: (key: string, value: string) => saved.set(key, value),
  } })
  saveAccessToken('test-key', 'http://server-a:8080')
  assert.equal(loadAccessToken('http://server-a:8080'), 'test-key')
  assert.equal(loadAccessToken('http://server-b:8080'), '')
  const calls: Array<{ url: string; headers: Headers }> = []
  Object.defineProperty(globalThis, 'fetch', { configurable: true, value: async (url: string, init: RequestInit) => {
    calls.push({ url, headers: new Headers(init.headers) })
    return new Response('{}', { status: url.includes('expired') ? 401 : 200 })
  } })
  await authorizedFetch('http://server-a:8080/api/trash')
  await authorizedFetch('http://server-b:8080/api/trash')
  assert.equal(calls[0].headers.get('Authorization'), 'Bearer test-key')
  assert.equal(calls[1].headers.get('Authorization'), null)
  await assert.rejects(authorizedFetch('http://server-a:8080/expired'), /ключ доступа/)
  await uploadAudio('http://server-a:8080', 'folder %', new Blob(['audio']), 'a.wav')
  assert.equal(calls[3].headers.get('X-File-Size'), '5')
  assert.match(calls[3].headers.get('X-Content-SHA256') ?? '', /^[0-9a-f]{64}$/)
  assert.match(calls[3].url, /folder%20%25/)
})
