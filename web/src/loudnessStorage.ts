import { readStorage, writeStorage } from './storage'

const LOUDNESS_KEY = 'music-web-loudness-normalize'

export function loadLoudnessNormalize() {
  const raw = readStorage(LOUDNESS_KEY)
  if (raw == null) return true
  return raw === '1' || raw === 'true'
}

export function saveLoudnessNormalize(enabled: boolean) {
  writeStorage(LOUDNESS_KEY, enabled ? '1' : '0')
}
