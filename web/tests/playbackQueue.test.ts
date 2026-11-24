import { test } from 'node:test'
import assert from 'node:assert/strict'
import { selectedQueue, enqueueNext, hasPriorityNext, advanceQueue, removeQueuedTrack } from '../src/playbackQueue.ts'
import type { MusicTrack } from '../src/types.ts'
const track = (id: string) => ({ id, title: id } as MusicTrack)
const [a,b,c,d] = ['A','B','C','D'].map(track)

test('play-next keeps the current track and takes precedence in either playback mode', () => {
  for (const shuffle of [false, true]) {
    let queue = selectedQueue([a,b,c,d], a)
    queue = enqueueNext(queue, c)
    queue = enqueueNext(queue, d)
    assert.equal(queue.entries[queue.index], a)
    assert.deepEqual(queue.entries.map(t => t.id), ['A','D','C','B'])
    for (const expected of ['D','C']) {
      assert.ok(hasPriorityNext(queue), `priority before ${shuffle ? 'random' : 'ordered'} selection`)
      queue = advanceQueue(queue)
      assert.equal(queue.entries[queue.index].id, expected)
      assert.equal(queue.entries[queue.index].playNext, false)
    }
    assert.equal(hasPriorityNext(queue), false)
    assert.equal(advanceQueue(queue).entries[3].id, 'B')
  }
})

test('a request to repeat current has a separate position and plays only once', () => {
  let queue = enqueueNext(selectedQueue([a,b,c], b), b)
  assert.equal(queue.index, 1)
  queue = advanceQueue(queue)
  assert.equal(queue.index, 2)
  assert.equal(queue.entries[queue.index].id, 'B')
  queue = advanceQueue(queue)
  assert.equal(queue.entries[queue.index].id, 'C')
})

test('idle play-next starts a queue, removal preserves the current occurrence', () => {
  assert.equal(enqueueNext({ entries: [], index: -1 }, a).index, 0)
  const queue = advanceQueue(enqueueNext(selectedQueue([a,b,c], a), b))
  const removed = removeQueuedTrack(queue, 'A')
  assert.equal(removed.entries[removed.index].id, 'B')
  assert.equal(removeQueuedTrack(removed, 'B').index, -1)
})
