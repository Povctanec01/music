import { useMemo, useState } from 'react'
import { TrackArt } from './TrackArt'
import { trimAudioFile } from './trimAudio'
import { WaveformTrimEditor } from './WaveformTrimEditor'
import type { MusicTrack } from './types'
import { uploadAudio } from './api'

type Props = {
  serverUrl: string
  tracks: MusicTrack[]
  loadingLibrary: boolean
  uploadDir: string
  onUploadDirChange: (value: string) => void
  onUploaded: () => Promise<void>
  onStatus: (message: string) => void
}

export function TrimPage(props: Props) {
  const { serverUrl, tracks, loadingLibrary, uploadDir, onUploadDirChange, onUploaded, onStatus } = props
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<MusicTrack | null>(null)
  const [duration, setDuration] = useState(0)
  const [startSec, setStartSec] = useState(0)
  const [endSec, setEndSec] = useState(0)
  const [busy, setBusy] = useState(false)

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return tracks
    return tracks.filter(
      (track) =>
        track.title.toLowerCase().includes(needle) ||
        track.artist.toLowerCase().includes(needle) ||
        track.album.toLowerCase().includes(needle) ||
        track.relativePath.toLowerCase().includes(needle),
    )
  }, [tracks, query])

  function selectTrack(track: MusicTrack) {
    setSelected(track)
    setDuration(track.durationMs > 0 ? track.durationMs / 1000 : 0)
    setStartSec(0)
    setEndSec(track.durationMs > 0 ? track.durationMs / 1000 : 0)
    onStatus(`Выбран: ${track.title}`)
  }

  function resetRange() {
    setStartSec(0)
    setEndSec(duration)
  }

  function closeEditor() {
    setSelected(null)
    setDuration(0)
    setStartSec(0)
    setEndSec(0)
  }

  async function exportAndUpload() {
    if (!selected?.remoteUrl || endSec <= startSec) return
    setBusy(true)
    onStatus('Обрезаю и загружаю…')
    try {
      const response = await fetch(selected.remoteUrl)
      if (!response.ok) throw new Error(`Не удалось загрузить файл: ${response.status}`)
      const sourceBlob = await response.blob()
      const blob = await trimAudioFile(sourceBlob, startSec, endSec)
      const baseName = selected.title.replace(/\.[^.]+$/, '') || selected.relativePath.replace(/\.[^.]+$/, '')
      const uploadName = `${baseName}.trim.${Math.round(startSec)}-${Math.round(endSec)}.wav`
      const base = serverUrl.replace(/\/$/, '')
      const uploadResponse = await uploadAudio(base, uploadDir, blob, uploadName)
      if (!uploadResponse.ok) throw new Error(`Ошибка загрузки: ${uploadResponse.status}`)
      onStatus(`Готово: ${uploadName}`)
      closeEditor()
      await onUploaded()
    } catch (error) {
      onStatus(error instanceof Error ? error.message : 'Не удалось обрезать файл')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="page-grid trim-page">
      {!selected ? (
        <>
          <article className="panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Редактор</p>
                <h2>Обрезка трека</h2>
              </div>
            </div>
            <p className="subtitle">Выбери трек из библиотеки Orange Pi — откроется редактор с волной и ползунками обрезки.</p>
            <div className="search-bar" style={{ marginTop: 12 }}>
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Поиск по названию, исполнителю, пути…"
              />
            </div>
            <label className="field" style={{ marginTop: 12 }}>
              Подпапка загрузки
              <input
                value={uploadDir}
                onChange={(event) => onUploadDirChange(event.target.value)}
                placeholder="Например: edits"
              />
            </label>
          </article>

          <article className="panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Библиотека</p>
                <h2>Выбери трек</h2>
              </div>
            </div>
            {loadingLibrary ? (
              <p className="subtitle">Загружаю список…</p>
            ) : !filtered.length ? (
              <div className="empty-state">
                <div className="empty-icon">🎵</div>
                <p>{tracks.length ? 'Ничего не найдено по запросу.' : 'На Orange Pi пока нет треков.'}</p>
              </div>
            ) : (
              <div className="list trim-track-list">
                {filtered.map((track) => (
                  <button
                    key={track.id}
                    type="button"
                    className="track-row trim-pick-row"
                    onClick={() => selectTrack(track)}
                  >
                    <div className="track-row-main">
                      <div className="track-avatar">
                        <TrackArt track={track} className="track-avatar-image" />
                      </div>
                      <div className="track-copy">
                        <strong>{track.title}</strong>
                        <span>
                          {track.artist || 'Неизвестный исполнитель'} • {track.album || track.relativePath}
                        </span>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </article>
        </>
      ) : selected.remoteUrl ? (
        <article className="panel trim-editor-panel">
          <WaveformTrimEditor
            key={selected.remoteUrl}
            audioUrl={selected.remoteUrl}
            fileName={selected.relativePath.split('/').pop() ?? selected.title}
            duration={duration}
            startSec={startSec}
            endSec={endSec}
            busy={busy}
            onStartChange={(value) => setStartSec(Math.max(0, Math.min(value, endSec - 0.05)))}
            onEndChange={(value) => setEndSec(Math.max(startSec + 0.05, Math.min(value, duration)))}
            onDurationChange={(value) => {
              setDuration(value)
              setEndSec((prev) => (prev <= 0 || prev > value ? value : prev))
            }}
            onReset={resetRange}
            onClose={closeEditor}
            onSave={() => void exportAndUpload()}
          />
        </article>
      ) : null}
    </section>
  )
}
