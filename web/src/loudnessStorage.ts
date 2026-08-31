const LOUDNESS_KEY = 'music-web-loudness-normalize'

export function loadLoudnessNormalize() {
  const raw = localStorage.getItem(LOUDNESS_KEY)
  if (raw == null) return true
  return raw === '1' || raw === 'true'
}

export function saveLoudnessNormalize(enabled: boolean) {
  localStorage.setItem(LOUDNESS_KEY, enabled ? '1' : '0')
}
