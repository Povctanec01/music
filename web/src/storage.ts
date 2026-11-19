import type { ListeningBehavior } from './types'

const FAVORITES_KEY = 'music-web-favorites'
const BEHAVIOR_KEY = 'music-web-behavior'
const SERVER_URL_KEY = 'music-web-server-url'
const GITHUB_REPO_KEY = 'music-web-github-repo'
const LIBRARY_TAB_KEY = 'music-web-library-tab'
const SORT_MODE_KEY = 'music-web-sort-mode'
const DEFAULT_GITHUB_REPO = 'Povctanec01/music'

export function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Playback remains usable when storage is blocked or full.
  }
}

export function loadFavorites(): string[] {
  try {
    const value: unknown = JSON.parse(readStorage(FAVORITES_KEY) ?? '[]')
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

export function saveFavorites(favorites: string[]) {
  writeStorage(FAVORITES_KEY, JSON.stringify(favorites))
}

export function loadBehavior(): Record<string, ListeningBehavior> {
  try {
    const value: unknown = JSON.parse(readStorage(BEHAVIOR_KEY) ?? '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(Object.entries(value).flatMap(([id, item]) => {
      if (!item || typeof item !== 'object') return []
      const safeNumber = (key: string) => {
        const n: unknown = item[key]
        return typeof n === 'number' && Number.isFinite(n) ? Math.max(0, n) : 0
      }
      return [[id, {
        playCount: safeNumber('playCount'), completionCount: safeNumber('completionCount'),
        skipCount: safeNumber('skipCount'), totalListenedMs: safeNumber('totalListenedMs'),
        lastPlayedAt: safeNumber('lastPlayedAt'),
      }]]
    }))
  } catch {
    return {}
  }
}

export function saveBehavior(behavior: Record<string, ListeningBehavior>) {
  writeStorage(BEHAVIOR_KEY, JSON.stringify(behavior))
}

export function loadServerUrl() {
  const saved = readStorage(SERVER_URL_KEY)
  if (typeof window === 'undefined') {
    return saved ?? 'http://192.168.1.80:8080'
  }

  const origin = window.location.origin
  const host = window.location.hostname
  const isLocalDev = host === 'localhost' || host === '127.0.0.1'

  // Site is served from Orange Pi itself (LAN or Tailscale).
  // Always use the current page origin so switching IPs just works.
  if (!isLocalDev) {
    return origin
  }

  return saved ?? 'http://192.168.1.80:8080'
}

export function saveServerUrl(url: string) {
  writeStorage(SERVER_URL_KEY, url)
}

export function loadGithubRepo() {
  return readStorage(GITHUB_REPO_KEY) ?? DEFAULT_GITHUB_REPO
}

export function saveGithubRepo(repo: string) {
  writeStorage(GITHUB_REPO_KEY, repo)
}

export function loadLibraryTab(): string {
  return readStorage(LIBRARY_TAB_KEY) ?? 'tracks'
}

export function saveLibraryTab(tab: string) {
  writeStorage(LIBRARY_TAB_KEY, tab)
}

export function loadSortMode() {
  return readStorage(SORT_MODE_KEY) ?? 'date_added'
}

export function saveSortMode(mode: string) {
  writeStorage(SORT_MODE_KEY, mode)
}

const VOLUME_KEY = 'music-web-volume'

export function loadVolume() {
  const raw = readStorage(VOLUME_KEY)
  const value = raw == null ? 1 : Number(raw)
  if (!Number.isFinite(value)) return 1
  return Math.min(1, Math.max(0, value))
}

export function saveVolume(volume: number) {
  writeStorage(VOLUME_KEY, String(Math.min(1, Math.max(0, volume))))
}

const SHUFFLE_KEY = 'music-web-shuffle'

export function loadShuffle() {
  return readStorage(SHUFFLE_KEY) === '1'
}

export function saveShuffle(enabled: boolean) {
  writeStorage(SHUFFLE_KEY, enabled ? '1' : '0')
}
