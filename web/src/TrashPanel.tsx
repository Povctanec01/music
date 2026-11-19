import { useState } from 'react'
import { authorizedFetch } from './api'

type Entry = { id: string; path: string; deletedAt: number }

export function TrashPanel({ serverUrl, onRestored }: { serverUrl: string; onRestored: () => Promise<void> }) {
  const [entries, setEntries] = useState<Entry[]>([])
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() {
    setBusy(true)
    try {
      const response = await authorizedFetch(`${serverUrl.replace(/\/$/, '')}/api/trash`)
      if (!response.ok) throw new Error(`Корзина: ${response.status}`)
      const data = await response.json() as { files: Entry[] }
      setEntries(data.files)
      setMessage(data.files.length ? '' : 'Корзина пуста')
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Не удалось открыть корзину') }
    finally { setBusy(false) }
  }
  async function restore(entry: Entry) {
    setBusy(true)
    try {
      const response = await authorizedFetch(`${serverUrl.replace(/\/$/, '')}/api/trash/${entry.id}/restore`, { method: 'POST' })
      if (!response.ok) throw new Error(response.status === 409 ? 'Файл с таким именем уже существует' : `Восстановление: ${response.status}`)
      await onRestored()
      await refresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Не удалось восстановить') }
    finally { setBusy(false) }
  }
  return <article className="panel">
    <h2>Корзина</h2>
    <button type="button" className="btn" disabled={busy} onClick={() => void refresh()}>Показать удалённые треки</button>
    {message && <p role="status">{message}</p>}
    {entries.map(entry => <div className="track-row" key={entry.id}>
      <span>{entry.path}</span>
      <button type="button" className="btn" disabled={busy} onClick={() => void restore(entry)}>Восстановить</button>
    </div>)}
  </article>
}
