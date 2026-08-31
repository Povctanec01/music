import type { ListeningBehavior } from './types'

const FAVORITES_KEY = 'music-web-favorites'
const BEHAVIOR_KEY = 'music-web-behavior'
const SERVER_URL_KEY = 'music-web-server-url'
const GITHUB_REPO_KEY = 'music-web-github-repo'
const LIBRARY_TAB_KEY = 'music-web-library-tab'
const SORT_MODE_KEY = 'music-web-sort-mode'
const DEFAULT_GITHUB_REPO = 'Povctanec01/music'

export function loadFavorites(): string[] {
  try {
    return JSON.parse(localStorage.getItem(FAVORITES_KEY) ?? '[]')
  } catch {
    return []
  }
}

export function saveFavorites(favorites: string[]) {
  localStorage.setItem(FAVORITES_KEY, JSON.stringify(favorites))
}

export function loadBehavior(): Record<string, ListeningBehavior> {
  try {
    return JSON.parse(localStorage.getItem(BEHAVIOR_KEY) ?? '{}')
  } catch {
    return {}
  }
}

export function saveBehavior(behavior: Record<string, ListeningBehavior>) {
  localStorage.setItem(BEHAVIOR_KEY, JSON.stringify(behavior))
}

export function loadServerUrl() {
  const saved = localStorage.getItem(SERVER_URL_KEY)
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
  localStorage.setItem(SERVER_URL_KEY, url)
}

export function loadGithubRepo() {
  return localStorage.getItem(GITHUB_REPO_KEY) ?? DEFAULT_GITHUB_REPO
}

export function saveGithubRepo(repo: string) {
  localStorage.setItem(GITHUB_REPO_KEY, repo)
}

export function loadLibraryTab(): string {
  return localStorage.getItem(LIBRARY_TAB_KEY) ?? 'tracks'
}

export function saveLibraryTab(tab: string) {
  localStorage.setItem(LIBRARY_TAB_KEY, tab)
}

export function loadSortMode() {
  return localStorage.getItem(SORT_MODE_KEY) ?? 'date_added'
}

export function saveSortMode(mode: string) {
  localStorage.setItem(SORT_MODE_KEY, mode)
}

const VOLUME_KEY = 'music-web-volume'

export function loadVolume() {
  const raw = localStorage.getItem(VOLUME_KEY)
  const value = raw == null ? 1 : Number(raw)
  if (!Number.isFinite(value)) return 1
  return Math.min(1, Math.max(0, value))
}

export function saveVolume(volume: number) {
  localStorage.setItem(VOLUME_KEY, String(Math.min(1, Math.max(0, volume))))
}

const SHUFFLE_KEY = 'music-web-shuffle'

export function loadShuffle() {
  return localStorage.getItem(SHUFFLE_KEY) === '1'
}

export function saveShuffle(enabled: boolean) {
  localStorage.setItem(SHUFFLE_KEY, enabled ? '1' : '0')
}
