import { sha256Blob } from './sha256.ts'

function tokenKey(baseUrl?: string) {
  return `music-server-token:${new URL(baseUrl || location.origin, location.origin).origin}`
}

export function loadAccessToken(baseUrl?: string): string {
  try { return sessionStorage.getItem(tokenKey(baseUrl)) ?? '' } catch { return '' }
}

export function saveAccessToken(token: string, baseUrl?: string) {
  try { sessionStorage.setItem(tokenKey(baseUrl), token.trim()) } catch { /* unavailable storage */ }
}

export async function authorizedFetch(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  const token = loadAccessToken(url)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(url, { ...init, headers })
  if (response.status === 401) throw new Error('Введите ключ доступа сервера в настройках')
  if (response.status === 503) throw new Error('Доступ на запись не настроен на сервере')
  return response
}
export async function uploadAudio(baseUrl: string, folder: string, blob: Blob, name: string) {
  const body = new FormData()
  body.append('files', blob, name)
  const headers = new Headers({ 'X-File-Size': String(blob.size) })
  headers.set('X-Content-SHA256', await sha256Blob(blob))
  const response = await authorizedFetch(`${baseUrl.replace(/\/$/, '')}/api/upload?path=${encodeURIComponent(folder)}`, { method: 'POST', headers, body })
  if (!response.ok) throw new Error(`Ошибка загрузки ${name}: ${response.status}`)
  return response
}
