import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadServerUrl } from '../src/storage.ts'

test('web sync uses the page origin for the new LAN and Tailscale endpoints', () => {
  const oldWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  const oldStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  try {
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => 'http://192.168.1.80:8080' } })
    for (const host of ['192.168.1.76', '100.96.151.91']) {
      Object.defineProperty(globalThis, 'window', { configurable: true, value: { location: { hostname: host, origin: `http://${host}:8080` } } })
      assert.equal(loadServerUrl(), `http://${host}:8080`)
    }
  } finally {
    if (oldWindow) Object.defineProperty(globalThis, 'window', oldWindow); else Reflect.deleteProperty(globalThis, 'window')
    if (oldStorage) Object.defineProperty(globalThis, 'localStorage', oldStorage); else Reflect.deleteProperty(globalThis, 'localStorage')
  }
})

test('local development defaults to the new board and preserves custom servers', () => {
  const oldWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  const oldStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  try {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { location: { hostname: 'localhost', origin: 'http://localhost:5173' } } })
    for (const saved of [null, 'http://192.168.1.80:8080', 'https://music.example.com']) {
      Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => saved } })
      assert.equal(loadServerUrl(), saved === 'https://music.example.com' ? saved : 'http://192.168.1.76:8080')
    }
  } finally {
    if (oldWindow) Object.defineProperty(globalThis, 'window', oldWindow); else Reflect.deleteProperty(globalThis, 'window')
    if (oldStorage) Object.defineProperty(globalThis, 'localStorage', oldStorage); else Reflect.deleteProperty(globalThis, 'localStorage')
  }
})
