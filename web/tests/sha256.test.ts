import { createHash } from 'node:crypto'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { sha256Blob } from '../src/sha256.ts'

test('streaming SHA-256 matches independent native hashes including block and chunk boundaries', async () => {
  assert.equal(await sha256Blob(new Blob()), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
  assert.equal(await sha256Blob(new Blob(['abc'])), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  for (const length of [1,55,56,63,64,65,127,128,129,1000,2*1024*1024+67]) {
    const bytes = Uint8Array.from({length}, (_, index) => (index * 131 + 17) & 255)
    assert.equal(await sha256Blob(new Blob([bytes])), createHash('sha256').update(bytes).digest('hex'), `length ${length}`)
  }
})
