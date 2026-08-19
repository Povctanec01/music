import { useEffect, useMemo, useRef, useState } from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { parseBlob } from 'music-metadata-browser'
import './App.css'
import { buildRecommendationSections, emptyBehavior } from './recommendations'
import {
  loadBehavior,
  loadFavorites,
  loadGithubRepo,
  loadServerUrl,
  saveBehavior,
  saveFavorites,
  saveGithubRepo,
  saveServerUrl,
} from './storage'
import type { ListeningBehavior, MusicTrack, RecommendationSection, ReleaseInfo } from './types'

function App() {
  const [serverUrl, setServerUrl] = useState(loadServerUrl)
  const [githubRepo, setGithubRepo] = useState(loadGithubRepo)
  const [localTracks, setLocalTracks] = useState<MusicTrack[]>([])
  const [remoteTracks, setRemoteTracks] = useState<MusicTrack[]>([])
  const [favorites, setFavorites] = useState<Set<string>>(new Set(loadFavorites()))
  const [behavior, setBehavior] = useState<Record<string, ListeningBehavior>>(loadBehavior())
  const [queue, setQueue] = useState<MusicTrack[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  const [isPlaying, setIsPlaying] = useState(false)
  const [positionMs, setPositionMs] = useState(0)
  const [durationMs, setDurationMs] = useState(0)
  const [loadingLocal, setLoadingLocal] = useState(false)
  const [loadingRemote, setLoadingRemote] = useState(false)
  const [loadingRelease, setLoadingRelease] = useState(false)
  const [latestRelease, setLatestRelease] = useState<ReleaseInfo | null>(null)
  const [status, setStatus] = useState('Выбери локальную папку или обнови библиотеку Orange Pi.')
  const [uploadDir, setUploadDir] = useState('')
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const lastTrackedPosition = useRef(0)
  const pendingListenedMs = useRef(0)
  const allTracks = useMemo(() => dedupeTracks([...localTracks, ...remoteTracks]), [localTracks, remoteTracks])
  const sections = useMemo(
    () => buildRecommendationSections(allTracks, behavior, favorites),
    [allTracks, behavior, favorites],
  )
  const currentIndex = queue.findIndex((track) => track.id === currentId)
  const currentTrack = currentIndex >= 0 ? queue[currentIndex] : null

  useEffect(() => {
    saveServerUrl(serverUrl)
  }, [serverUrl])

  useEffect(() => {
    saveGithubRepo(githubRepo)
  }, [githubRepo])

  useEffect(() => {
    saveFavorites([...favorites])
  }, [favorites])

  useEffect(() => {
    saveBehavior(behavior)
  }, [behavior])

  useEffect(() => {
    void refreshRemoteLibrary()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!githubRepo.trim()) {
      setLatestRelease(null)
      return
    }
    void fetchLatestRelease()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [githubRepo, serverUrl])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio || !currentTrack) return
    const source = currentTrack.objectUrl ?? currentTrack.remoteUrl
    if (!source) return
    audio.src = source
    void audio.play()
    setIsPlaying(true)
    setPositionMs(0)
    setDurationMs(currentTrack.durationMs)
    lastTrackedPosition.current = 0
    pendingListenedMs.current = 0
    updateBehavior(currentTrack.id, (current) => ({
      ...current,
      playCount: current.playCount + 1,
      lastPlayedAt: Date.now(),
    }))
  }, [currentTrack])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    const onPlay = () => setIsPlaying(true)
    const onPause = () => setIsPlaying(false)
    const onLoadedMetadata = () => {
      setDurationMs(Number.isFinite(audio.duration) ? audio.duration * 1000 : durationMs)
    }
    const onTimeUpdate = () => {
      const nextPosition = audio.currentTime * 1000
      setPositionMs(nextPosition)
      const delta = Math.max(0, nextPosition - lastTrackedPosition.current)
      if (delta > 0 && delta < 5_000 && currentTrack) {
        pendingListenedMs.current += delta
        if (pendingListenedMs.current >= 5_000) {
          const listened = pendingListenedMs.current
          pendingListenedMs.current = 0
          updateBehavior(currentTrack.id, (current) => ({
            ...current,
            totalListenedMs: current.totalListenedMs + listened,
          }))
        }
      }
      lastTrackedPosition.current = nextPosition
    }
    const onEnded = () => {
      if (currentTrack) {
        updateBehavior(currentTrack.id, (current) => ({
          ...current,
          completionCount: current.completionCount + 1,
        }))
      }
      playNext()
    }
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('loadedmetadata', onLoadedMetadata)
    audio.addEventListener('timeupdate', onTimeUpdate)
    audio.addEventListener('ended', onEnded)
    return () => {
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('loadedmetadata', onLoadedMetadata)
      audio.removeEventListener('timeupdate', onTimeUpdate)
      audio.removeEventListener('ended', onEnded)
    }
  }, [currentTrack, durationMs])

  async function chooseLocalFolder() {
    const picker = (window as Window & { showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle> })
      .showDirectoryPicker
    if (!picker) {
      setStatus('В этом браузере нет выбора папки. Используй импорт файлов ниже.')
      return
    }
    setLoadingLocal(true)
    setStatus('Сканирую локальную папку…')
    try {
      const handle = await picker()
      const files = await scanDirectory(handle)
      setLocalTracks(files)
      setStatus(`Локально найдено ${files.length} треков.`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось открыть папку')
    } finally {
      setLoadingLocal(false)
    }
  }

  async function importFiles(files: FileList | null) {
    if (!files?.length) return
    setLoadingLocal(true)
    setStatus('Импортирую выбранные файлы…')
    try {
      const tracks = await Promise.all(
        [...files]
          .filter((file) => isAudioFile(file.name))
          .map((file) => createTrackFromFile(file, file.name)),
      )
      setLocalTracks(tracks)
      setStatus(`Импортировано ${tracks.length} локальных треков.`)
    } finally {
      setLoadingLocal(false)
    }
  }

  async function refreshRemoteLibrary() {
    setLoadingRemote(true)
    try {
      const response = await fetch(`${serverUrl.replace(/\/$/, '')}/api/library`)
      if (!response.ok) throw new Error(`Orange Pi API: ${response.status}`)
      const payload = (await response.json()) as { files: Array<{ path: string; size: number }> }
      const tracks = payload.files.map((item) => createRemoteTrack(item.path, item.size, serverUrl))
      setRemoteTracks(tracks)
      setStatus(`На Orange Pi найдено ${tracks.length} файлов.`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось получить библиотеку с Orange Pi')
    } finally {
      setLoadingRemote(false)
    }
  }

  async function uploadFiles(files: FileList | null) {
    if (!files?.length) return
    setStatus('Загружаю файлы на Orange Pi…')
    const base = serverUrl.replace(/\/$/, '')
    for (const file of [...files]) {
      const formData = new FormData()
      formData.append('files', file, file.name)
      const response = await fetch(`${base}/api/upload?path=${encodeURIComponent(uploadDir)}`, {
        method: 'POST',
        body: formData,
      })
      if (!response.ok) {
        throw new Error(`Ошибка загрузки ${file.name}: ${response.status}`)
      }
    }
    setStatus(`Загружено ${files.length} файлов на Orange Pi.`)
    await refreshRemoteLibrary()
  }

  async function fetchLatestRelease() {
    const repo = githubRepo.trim()
    if (!repo) {
      setLatestRelease(null)
      return
    }
    setLoadingRelease(true)
    try {
      const base = serverUrl.replace(/\/$/, '')
      const response = await fetch(`${base}/api/releases/latest?repo=${encodeURIComponent(repo)}`)
      if (!response.ok) throw new Error(`Release API: ${response.status}`)
      const payload = (await response.json()) as ReleaseInfo
      setLatestRelease(payload)
    } catch (error) {
      setLatestRelease(null)
      setStatus(error instanceof Error ? error.message : 'Не удалось получить данные о релизе APK')
    } finally {
      setLoadingRelease(false)
    }
  }

  function playSelection(track: MusicTrack, tracks: MusicTrack[]) {
    setQueue(tracks)
    setCurrentId(track.id)
  }

  function toggleFavorite(trackId: string) {
    setFavorites((current) => {
      const next = new Set(current)
      if (next.has(trackId)) next.delete(trackId)
      else next.add(trackId)
      return next
    })
  }

  function updateBehavior(trackId: string, mutate: (current: ListeningBehavior) => ListeningBehavior) {
    setBehavior((current) => ({
      ...current,
      [trackId]: mutate(current[trackId] ?? emptyBehavior()),
    }))
  }

  function togglePlayback() {
    const audio = audioRef.current
    if (!audio || !currentTrack) return
    if (audio.paused) void audio.play()
    else audio.pause()
  }

  function playNext() {
    if (currentIndex < 0 || currentIndex >= queue.length - 1) {
      setCurrentId(null)
      setIsPlaying(false)
      return
    }
    setCurrentId(queue[currentIndex + 1].id)
  }

  function playPrevious() {
    if (currentIndex <= 0) return
    maybeRecordSkip()
    setCurrentId(queue[currentIndex - 1].id)
  }

  function maybeRecordSkip() {
    if (!currentTrack || !durationMs) return
    const progress = positionMs / durationMs
    if (positionMs > 5_000 && progress < 0.35) {
      updateBehavior(currentTrack.id, (current) => ({
        ...current,
        skipCount: current.skipCount + 1,
      }))
    }
  }

  function seekTo(event: React.ChangeEvent<HTMLInputElement>) {
    const audio = audioRef.current
    if (!audio) return
    const next = Number(event.target.value)
    audio.currentTime = next / 1000
    setPositionMs(next)
  }

  return (
    <div className="app-shell">
      <audio ref={audioRef} preload="metadata" />
      <header className="hero-card">
        <div>
          <p className="eyebrow">Music Web</p>
          <h1>Веб-плеер для локальной музыки и Orange Pi</h1>
          <p className="subtitle">
            Локальная библиотека, проигрывание, рекомендации, загрузка и скачивание в одном PWA.
          </p>
          <div className="hero-links">
            <a href={`${serverUrl.replace(/\/$/, '')}/api/health`} target="_blank" rel="noreferrer">
              API health
            </a>
            <a href={`${serverUrl.replace(/\/$/, '')}/api/library`} target="_blank" rel="noreferrer">
              Библиотека
            </a>
            {latestRelease?.apkUrl ? (
              <a href={latestRelease.apkUrl} target="_blank" rel="noreferrer">
                Скачать APK
              </a>
            ) : null}
          </div>
          <p className="subtitle">
            {githubRepo.trim()
              ? loadingRelease
                ? 'Проверяю последнюю APK-версию…'
                : latestRelease?.versionName
                  ? `Последняя версия Android: ${latestRelease.versionName} (${latestRelease.versionCode ?? '?'})`
                  : 'Укажи корректный GitHub release с APK и тегом вида v1.2.0+12.'
              : 'Укажи GitHub repo в настройках, чтобы включить APK-обновления.'}
          </p>
        </div>
        <div className="hero-stats">
          <StatCard label="Локально" value={String(localTracks.length)} />
          <StatCard label="Orange Pi" value={String(remoteTracks.length)} />
          <StatCard label="Избранное" value={String(favorites.size)} />
        </div>
      </header>

      <nav className="top-nav">
        <NavLink to="/">Главная</NavLink>
        <NavLink to="/sync">Синк</NavLink>
        <NavLink to="/settings">Настройки</NavLink>
      </nav>

      <p className="status-line">{status}</p>

      <Routes>
        <Route
          path="/"
          element={
            <HomePage
              localTracks={localTracks}
              sections={sections}
              currentId={currentId}
              favorites={favorites}
              latestRelease={latestRelease}
              loadingRelease={loadingRelease}
              onPickFolder={chooseLocalFolder}
              onImportFiles={importFiles}
              onToggleFavorite={toggleFavorite}
              onPlay={(track, tracks) => playSelection(track, tracks)}
              loadingLocal={loadingLocal}
            />
          }
        />
        <Route
          path="/sync"
          element={
            <SyncPage
              serverUrl={serverUrl}
              remoteTracks={remoteTracks}
              loadingRemote={loadingRemote}
              uploadDir={uploadDir}
              onUploadDirChange={setUploadDir}
              onRefresh={refreshRemoteLibrary}
              onUpload={uploadFiles}
              onPlay={(track) => playSelection(track, remoteTracks)}
            />
          }
        />
        <Route
          path="/settings"
          element={
            <SettingsPage
              serverUrl={serverUrl}
              githubRepo={githubRepo}
              onServerUrlChange={setServerUrl}
              onGithubRepoChange={setGithubRepo}
              onRefresh={refreshRemoteLibrary}
              onRefreshRelease={fetchLatestRelease}
            />
          }
        />
      </Routes>

      {currentTrack ? (
        <footer className="player-panel">
          <div className="player-main">
            <div className="cover-tile">{(currentTrack.title[0] ?? '♪').toUpperCase()}</div>
            <div className="player-meta">
              <strong>{currentTrack.title}</strong>
              <span>{currentTrack.artist || 'Неизвестный исполнитель'}</span>
            </div>
            <div className="player-controls">
              <button type="button" onClick={playPrevious}>
                Назад
              </button>
              <button type="button" onClick={togglePlayback}>
                {isPlaying ? 'Пауза' : 'Играть'}
              </button>
              <button
                type="button"
                onClick={() => {
                  maybeRecordSkip()
                  playNext()
                }}
              >
                Далее
              </button>
            </div>
          </div>
          <input
            className="seekbar"
            type="range"
            min={0}
            max={Math.max(durationMs, 1)}
            value={Math.min(positionMs, Math.max(durationMs, 1))}
            onChange={seekTo}
          />
          <div className="player-times">
            <span>{formatDuration(positionMs)}</span>
            <span>{formatDuration(durationMs)}</span>
          </div>
        </footer>
      ) : null}
    </div>
  )
}

export default App

function HomePage(props: {
  localTracks: MusicTrack[]
  sections: RecommendationSection[]
  currentId: string | null
  favorites: Set<string>
  latestRelease: ReleaseInfo | null
  loadingRelease: boolean
  onPickFolder: () => Promise<void>
  onImportFiles: (files: FileList | null) => Promise<void>
  onToggleFavorite: (trackId: string) => void
  onPlay: (track: MusicTrack, tracks: MusicTrack[]) => void
  loadingLocal: boolean
}) {
  const {
    localTracks,
    sections,
    currentId,
    favorites,
    latestRelease,
    loadingRelease,
    onPickFolder,
    onImportFiles,
    onToggleFavorite,
    onPlay,
    loadingLocal,
  } = props

  return (
    <section className="page-grid">
      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Android APK</p>
            <h2>Обновления для телефона</h2>
          </div>
          {latestRelease?.apkUrl ? (
            <a className="secondary-button" href={latestRelease.apkUrl} target="_blank" rel="noreferrer">
              Скачать APK
            </a>
          ) : null}
        </div>
        <p>
          {loadingRelease
            ? 'Проверяю последнюю версию…'
            : latestRelease?.versionName
              ? `Последний релиз: ${latestRelease.versionName} (${latestRelease.versionCode ?? '?'})`
              : 'Добавь GitHub repo в настройках и публикуй APK как release asset.'}
        </p>
        {latestRelease?.body ? <p>{latestRelease.body.split('\n').find((line) => line.trim())}</p> : null}
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Локальная музыка</p>
            <h2>Библиотека с ПК</h2>
          </div>
          <div className="actions">
            <button type="button" onClick={() => void onPickFolder()}>
              Выбрать папку
            </button>
            <label className="secondary-button">
              Импорт файлов
              <input
                type="file"
                accept=".mp3,.flac,.ogg,.opus,.m4a,.aac,.wav"
                multiple
                hidden
                onChange={(event) => void onImportFiles(event.target.files)}
              />
            </label>
          </div>
        </div>
        {loadingLocal ? <p>Сканирование локальной папки…</p> : null}
        {!localTracks.length ? <p>Пока нет локальных треков. Выбери папку или импортируй файлы вручную.</p> : null}
      </article>

      {sections.map((section) => (
        <article className="panel" key={section.key}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">{section.title}</p>
              <h2>{section.subtitle}</h2>
            </div>
          </div>
          <div className="card-row">
            {section.tracks.map((track) => (
              <TrackCard
                key={track.id}
                track={track}
                active={track.id === currentId}
                favorite={favorites.has(track.id)}
                onPlay={() => onPlay(track, section.tracks)}
                onToggleFavorite={() => onToggleFavorite(track.id)}
              />
            ))}
          </div>
        </article>
      ))}

      {!!localTracks.length && (
        <article className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Все локальные треки</p>
              <h2>Плотный список для быстрого выбора</h2>
            </div>
          </div>
          <div className="list">
            {localTracks.map((track) => (
              <TrackRow
                key={track.id}
                track={track}
                active={track.id === currentId}
                favorite={favorites.has(track.id)}
                onPlay={() => onPlay(track, localTracks)}
                onToggleFavorite={() => onToggleFavorite(track.id)}
              />
            ))}
          </div>
        </article>
      )}
    </section>
  )
}

function SyncPage(props: {
  serverUrl: string
  remoteTracks: MusicTrack[]
  loadingRemote: boolean
  uploadDir: string
  onUploadDirChange: (value: string) => void
  onRefresh: () => Promise<void>
  onUpload: (files: FileList | null) => Promise<void>
  onPlay: (track: MusicTrack) => void
}) {
  const { serverUrl, remoteTracks, loadingRemote, uploadDir, onUploadDirChange, onRefresh, onUpload, onPlay } = props

  return (
    <section className="page-grid">
      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Orange Pi</p>
            <h2>Удалённая библиотека</h2>
          </div>
          <button type="button" onClick={() => void onRefresh()}>
            Обновить
          </button>
        </div>
        <p>{loadingRemote ? 'Загружаю удалённую библиотеку…' : `Источник: ${serverUrl}`}</p>
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Upload</p>
            <h2>Загрузка с ПК на Orange Pi</h2>
          </div>
        </div>
        <label className="field">
          Подпапка внутри /srv/music
          <input value={uploadDir} onChange={(event) => onUploadDirChange(event.target.value)} placeholder="Например: New arrivals/2026" />
        </label>
        <label className="secondary-button">
          Выбрать и загрузить файлы
          <input
            type="file"
            accept=".mp3,.flac,.ogg,.opus,.m4a,.aac,.wav"
            multiple
            hidden
            onChange={(event) => void onUpload(event.target.files)}
          />
        </label>
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Remote files</p>
            <h2>Треки на Orange Pi</h2>
          </div>
        </div>
        <div className="list">
          {remoteTracks.map((track) => (
            <div className="track-row" key={track.id}>
              <div className="track-avatar">{(track.title[0] ?? '♪').toUpperCase()}</div>
              <div className="track-copy">
                <strong>{track.title}</strong>
                <span>{track.relativePath}</span>
              </div>
              <div className="actions">
                <button type="button" onClick={() => onPlay(track)}>
                  Играть
                </button>
                <a href={track.remoteUrl} download>
                  Скачать
                </a>
              </div>
            </div>
          ))}
        </div>
      </article>
    </section>
  )
}

function SettingsPage(props: {
  serverUrl: string
  githubRepo: string
  onServerUrlChange: (value: string) => void
  onGithubRepoChange: (value: string) => void
  onRefresh: () => Promise<void>
  onRefreshRelease: () => Promise<void>
}) {
  const { serverUrl, githubRepo, onServerUrlChange, onGithubRepoChange, onRefresh, onRefreshRelease } = props
  const hasDirectoryPicker = typeof (window as Window & { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function'

  return (
    <section className="page-grid">
      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Подключение</p>
            <h2>Адрес Orange Pi API</h2>
          </div>
        </div>
        <label className="field">
          Базовый URL
          <input value={serverUrl} onChange={(event) => onServerUrlChange(event.target.value)} />
        </label>
        <label className="field">
          GitHub repo
          <input value={githubRepo} onChange={(event) => onGithubRepoChange(event.target.value)} placeholder="owner/repo" />
        </label>
        <div className="actions">
          <button type="button" onClick={() => void onRefresh()}>
            Проверить и обновить
          </button>
          <button type="button" onClick={() => void onRefreshRelease()}>
            Проверить APK release
          </button>
        </div>
        <p>Формат тега релиза: <code>v1.2.0+12</code>, где <code>12</code> это Android versionCode.</p>
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Совместимость</p>
            <h2>Что умеет текущий браузер</h2>
          </div>
        </div>
        <div className="caps">
          <StatCard label="Выбор папки" value={hasDirectoryPicker ? 'Да' : 'Fallback'} />
          <StatCard label="PWA" value={'Да'} />
          <StatCard label="Upload" value={'Да'} />
        </div>
      </article>
    </section>
  )
}

function TrackCard(props: {
  track: MusicTrack
  active: boolean
  favorite: boolean
  onPlay: () => void
  onToggleFavorite: () => void
}) {
  const { track, active, favorite, onPlay, onToggleFavorite } = props
  return (
    <button type="button" className={`track-card ${active ? 'active' : ''}`} onClick={onPlay}>
      <div className="artwork">{track.artUrl ? <img src={track.artUrl} alt="" /> : <span>{(track.title[0] ?? '♪').toUpperCase()}</span>}</div>
      <div className="track-copy">
        <strong>{track.title}</strong>
        <span>{track.artist || 'Неизвестный исполнитель'}</span>
      </div>
      <div className="card-footer">
        <span>{formatDuration(track.durationMs)}</span>
        <span
          className={`favorite ${favorite ? 'is-favorite' : ''}`}
          onClick={(event) => {
            event.stopPropagation()
            onToggleFavorite()
          }}
        >
          {favorite ? '♥' : '♡'}
        </span>
      </div>
    </button>
  )
}

function TrackRow(props: {
  track: MusicTrack
  active: boolean
  favorite: boolean
  onPlay: () => void
  onToggleFavorite: () => void
}) {
  const { track, active, favorite, onPlay, onToggleFavorite } = props
  return (
    <div className={`track-row ${active ? 'active' : ''}`}>
      <button type="button" className="track-row-main" onClick={onPlay}>
        <div className="track-avatar">{(track.title[0] ?? '♪').toUpperCase()}</div>
        <div className="track-copy">
          <strong>{track.title}</strong>
          <span>
            {track.artist || 'Неизвестный исполнитель'} • {track.album || 'Неизвестный альбом'}
          </span>
        </div>
        <span>{formatDuration(track.durationMs)}</span>
      </button>
      <button type="button" className={`favorite plain ${favorite ? 'is-favorite' : ''}`} onClick={onToggleFavorite}>
        {favorite ? '♥' : '♡'}
      </button>
    </div>
  )
}

function StatCard(props: { label: string; value: string }) {
  return (
    <div className="stat-card">
      <strong>{props.value}</strong>
      <span>{props.label}</span>
    </div>
  )
}

function formatDuration(durationMs: number) {
  if (!Number.isFinite(durationMs) || durationMs <= 0) return '0:00'
  const totalSeconds = Math.floor(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function dedupeTracks(tracks: MusicTrack[]) {
  const seen = new Set<string>()
  return tracks.filter((track) => {
    if (seen.has(track.id)) return false
    seen.add(track.id)
    return true
  })
}

function createRemoteTrack(path: string, size: number, baseUrl: string): MusicTrack {
  const title = path.split('/').pop()?.replace(/\.[^.]+$/, '') ?? path
  return {
    id: `remote:${path}`,
    title,
    artist: '',
    album: '',
    durationMs: 0,
    size,
    relativePath: path,
    source: 'remote',
    remoteUrl: `${baseUrl.replace(/\/$/, '')}/files/${path.split('/').map(encodeURIComponent).join('/')}`,
  }
}

async function scanDirectory(
  handle: FileSystemDirectoryHandle,
  prefix = '',
): Promise<MusicTrack[]> {
  const tracks: MusicTrack[] = []
  for await (const entry of handle.values()) {
    const nextPath = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.kind === 'directory') {
      tracks.push(...(await scanDirectory(entry, nextPath)))
      continue
    }
    if (!isAudioFile(entry.name)) continue
    const file = await entry.getFile()
    tracks.push(await createTrackFromFile(file, nextPath))
  }
  return tracks
}

async function createTrackFromFile(file: File, relativePath: string): Promise<MusicTrack> {
  const metadata = await parseBlob(file).catch(() => null)
  const picture = metadata?.common.picture?.[0]
  const artBytes = picture
    ? (() => {
        const bytes = new Uint8Array(picture.data.byteLength)
        bytes.set(picture.data)
        return bytes
      })()
    : undefined
  const artUrl = picture
    ? URL.createObjectURL(
        new Blob([artBytes!], {
          type: picture.format,
        }),
      )
    : undefined
  return {
    id: `local:${relativePath}:${file.lastModified}:${file.size}`,
    title: metadata?.common.title || file.name.replace(/\.[^.]+$/, ''),
    artist: metadata?.common.artist || '',
    album: metadata?.common.album || '',
    durationMs: Math.round((metadata?.format.duration ?? 0) * 1000),
    size: file.size,
    relativePath,
    source: 'local',
    artUrl,
    objectUrl: URL.createObjectURL(file),
    file,
  }
}

function isAudioFile(name: string) {
  return /\.(mp3|flac|ogg|opus|m4a|aac|wav)$/i.test(name)
}
