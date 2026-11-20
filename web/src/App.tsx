import { useEffect, useEffectEvent, useMemo, useRef, useState } from 'react'
import { NavLink, Route, Routes, useLocation } from 'react-router-dom'
import './App.css'
import { favoriteTracks, groupTracks, LIBRARY_TABS, SORT_MODES, sortTracks, type LibraryTab, type SortMode, formatRelativeAddedAt, groupNewestAddedAt } from './library'
import { createLoudnessController, type LoudnessController } from './loudness'
import { loadLoudnessNormalize, saveLoudnessNormalize } from './loudnessStorage'
import { buildRecommendationSections, emptyBehavior, pickWeightedShuffle } from './recommendations'
import {
  loadBehavior,
  loadFavorites,
  loadGithubRepo,
  loadLibraryTab,
  loadServerUrl,
  loadShuffle,
  loadSortMode,
  loadVolume,
  saveBehavior,
  saveFavorites,
  saveGithubRepo,
  saveLibraryTab,
  saveServerUrl,
  saveShuffle,
  saveSortMode,
  saveVolume,
} from './storage'
import { TrimPage } from './TrimPage'
import { TrackArt } from './TrackArt'
import { authorizedFetch, loadAccessToken, saveAccessToken, uploadAudio } from './api'
import { TrashPanel } from './TrashPanel'
import { registerMediaActions, updateMediaMetadata, updateMediaPlayback, updateMediaPosition } from './mediaSession'
import type { ListeningBehavior, MusicTrack, RecommendationSection, ReleaseInfo } from './types'

function App() {
  const [serverUrl, setServerUrl] = useState(loadServerUrl)
  const [lastDeletedId, setLastDeletedId] = useState<string | null>(null)
  const [githubRepo, setGithubRepo] = useState(loadGithubRepo)
  const [remoteTracks, setRemoteTracks] = useState<MusicTrack[]>([])
  const [favorites, setFavorites] = useState<Set<string>>(new Set(loadFavorites()))
  const [behavior, setBehavior] = useState<Record<string, ListeningBehavior>>(loadBehavior())
  const [queue, setQueue] = useState<MusicTrack[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  const [playbackRevision, setPlaybackRevision] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [positionMs, setPositionMs] = useState(0)
  const [durationMs, setDurationMs] = useState(0)
  const [loadingRemote, setLoadingRemote] = useState(false)
  const [loadingRelease, setLoadingRelease] = useState(false)
  const [latestRelease, setLatestRelease] = useState<ReleaseInfo | null>(null)
  const [status, setStatus] = useState('Загружаю библиотеку с Orange Pi…')
  const [uploadDir, setUploadDir] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [libraryTab, setLibraryTab] = useState<LibraryTab>(() => {
    const saved = loadLibraryTab()
    return LIBRARY_TABS.some((tab) => tab.id === saved) ? (saved as LibraryTab) : 'tracks'
  })
  const [sortMode, setSortMode] = useState<SortMode>(() => {
    const saved = loadSortMode()
    return SORT_MODES.some((mode) => mode.id === saved) ? (saved as SortMode) : 'date_added'
  })
  const [volume, setVolume] = useState(loadVolume)
  const [loudnessNormalize, setLoudnessNormalize] = useState(loadLoudnessNormalize)
  const [shuffle, setShuffle] = useState(loadShuffle)
  const [serverOnline, setServerOnline] = useState<boolean | null>(null)
  const [expandedGroup, setExpandedGroup] = useState<string | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const loudnessRef = useRef<LoudnessController | null>(null)
  const playerBarRef = useRef<HTMLElement | null>(null)
  const recentShuffleIdsRef = useRef<string[]>([])
  const location = useLocation()
  const lastTrackedPosition = useRef(0)
  const pendingListenedMs = useRef(0)
  const sections = useMemo(
    () => buildRecommendationSections(remoteTracks, behavior, favorites),
    [remoteTracks, behavior, favorites],
  )
  const visibleTracks = useMemo(
    () => sortTracks(filterTracks(remoteTracks, searchQuery), sortMode),
    [remoteTracks, searchQuery, sortMode],
  )
  const currentIndex = queue.findIndex((track) => track.id === currentId)
  const currentTrack = currentIndex >= 0 ? queue[currentIndex] : null
  const hasTrack = currentTrack !== null
  const canGoNext = hasTrack && (shuffle ? (remoteTracks.length || queue.length) > 1 : currentIndex < queue.length - 1)
  const canGoPrevious = hasTrack && (shuffle ? recentShuffleIdsRef.current.length >= 2 : currentIndex > 0)

  const mediaPlay = useEffectEvent(() => resumePlayback())
  const mediaPause = useEffectEvent(() => audioRef.current?.pause())
  const mediaNext = useEffectEvent(() => { maybeRecordSkip(); playNext() })
  const mediaPrevious = useEffectEvent(() => playPrevious())
  const mediaSeek = useEffectEvent((details: MediaSessionActionDetails) => {
    const audio = audioRef.current
    if (!audio || !Number.isFinite(details.seekTime) || !Number.isFinite(audio.duration)) return
    audio.currentTime = Math.max(0, Math.min(audio.duration, details.seekTime!))
    lastTrackedPosition.current = audio.currentTime * 1000
    setPositionMs(audio.currentTime * 1000)
  })

  useEffect(() => registerMediaActions(navigator.mediaSession, {
    play: hasTrack ? () => mediaPlay() : null,
    pause: hasTrack ? () => mediaPause() : null,
    nexttrack: canGoNext ? () => mediaNext() : null,
    previoustrack: canGoPrevious ? () => mediaPrevious() : null,
    seekto: hasTrack ? (details) => mediaSeek(details) : null,
  }), [hasTrack, canGoNext, canGoPrevious])

  useEffect(() => { updateMediaMetadata(navigator.mediaSession, currentTrack) }, [currentTrack])
  useEffect(() => { updateMediaPlayback(navigator.mediaSession, hasTrack, isPlaying) }, [hasTrack, isPlaying])
  useEffect(() => { updateMediaPosition(navigator.mediaSession, hasTrack ? durationMs : 0, positionMs) }, [hasTrack, durationMs, positionMs])
  useEffect(() => () => {
    updateMediaMetadata(navigator.mediaSession, null)
    updateMediaPlayback(navigator.mediaSession, false, false)
    updateMediaPosition(navigator.mediaSession, 0, 0)
  }, [])

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
    saveLibraryTab(libraryTab)
  }, [libraryTab])

  useEffect(() => {
    saveSortMode(sortMode)
  }, [sortMode])

  useEffect(() => {
    saveVolume(volume)
    loudnessRef.current?.setMasterVolume(volume)
    const audio = audioRef.current
    if (audio && !loudnessRef.current) audio.volume = volume
  }, [volume])

  useEffect(() => {
    saveShuffle(shuffle)
  }, [shuffle])

  useEffect(() => {
    saveLoudnessNormalize(loudnessNormalize)
    loudnessRef.current?.setEnabled(loudnessNormalize)
  }, [loudnessNormalize])

  const initializeLoudness = useEffectEvent(() => {
    const audio = audioRef.current
    if (!audio || loudnessRef.current) return
    loudnessRef.current = createLoudnessController(audio)
    loudnessRef.current.setEnabled(loudnessNormalize)
    loudnessRef.current.setMasterVolume(volume)
    loudnessRef.current.resetForTrack()
  })

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    // Create the Web Audio source on first playback. StrictMode rehearses effect
    // setup/cleanup at mount; an element can only have one media source node.
    audio.addEventListener('play', initializeLoudness)
    return () => {
      audio.removeEventListener('play', initializeLoudness)
      loudnessRef.current?.dispose()
      loudnessRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const el = playerBarRef.current
    if (!currentTrack || !el) {
      document.documentElement.style.removeProperty('--player-bar-height')
      return
    }
    const apply = () => {
      document.documentElement.style.setProperty('--player-bar-height', `${el.offsetHeight}px`)
    }
    apply()
    const observer = new ResizeObserver(apply)
    observer.observe(el)
    return () => {
      observer.disconnect()
      document.documentElement.style.removeProperty('--player-bar-height')
    }
  }, [currentTrack])

  useEffect(() => {
    void refreshRemoteLibrary()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverUrl])

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
    if (!audio) return
    if (!currentTrack) {
      audio.pause()
      audio.removeAttribute('src')
      audio.load()
      setIsPlaying(false)
      return
    }
    const source = currentTrack.objectUrl ?? currentTrack.remoteUrl
    if (!source) return
    audio.src = source
    loudnessRef.current?.resetForTrack()
    void audio.play().catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setIsPlaying(false)
      setStatus(error instanceof Error ? error.message : 'Не удалось воспроизвести трек')
    })
    setPositionMs(0)
    setDurationMs(currentTrack.durationMs)
    lastTrackedPosition.current = 0
    pendingListenedMs.current = 0
    updateBehavior(currentTrack.id, (current) => ({
      ...current,
      playCount: current.playCount + 1,
      lastPlayedAt: Date.now(),
    }))
    return () => {
      const listened = pendingListenedMs.current
      pendingListenedMs.current = 0
      if (listened > 0) updateBehavior(currentTrack.id, (current) => ({
        ...current, totalListenedMs: current.totalListenedMs + listened,
      }))
    }
  }, [currentTrack, playbackRevision])

  const handleEnded = useEffectEvent(() => {
    if (currentTrack) updateBehavior(currentTrack.id, (current) => ({
      ...current, completionCount: current.completionCount + 1,
    }))
    playNext()
  })

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    const onPlay = () => setIsPlaying(true)
    const onPause = () => {
      setIsPlaying(false)
      const listened = pendingListenedMs.current
      pendingListenedMs.current = 0
      if (currentTrack && listened > 0) updateBehavior(currentTrack.id, (current) => ({
        ...current, totalListenedMs: current.totalListenedMs + listened,
      }))
    }
    const onError = () => {
      setIsPlaying(false)
      setStatus(audio.error?.message || 'Ошибка воспроизведения аудио')
    }
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
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('loadedmetadata', onLoadedMetadata)
    audio.addEventListener('timeupdate', onTimeUpdate)
    audio.addEventListener('ended', handleEnded)
    audio.addEventListener('error', onError)
    return () => {
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('loadedmetadata', onLoadedMetadata)
      audio.removeEventListener('timeupdate', onTimeUpdate)
      audio.removeEventListener('ended', handleEnded)
      audio.removeEventListener('error', onError)
    }
  }, [currentTrack, durationMs, shuffle, queue, currentIndex, remoteTracks, behavior, favorites])

  useEffect(() => {
    if (shuffle && remoteTracks.length > 1) {
      setQueue((current) => (remoteTracks.length > current.length ? remoteTracks : current))
    }
  }, [shuffle, remoteTracks])

  async function refreshRemoteLibrary() {
    setLoadingRemote(true)
    try {
      const response = await fetch(`${serverUrl.replace(/\/$/, '')}/api/library`)
      if (!response.ok) throw new Error(`Orange Pi API: ${response.status}`)
      const payload = (await response.json()) as { files: Array<{ path: string; size: number; mtime: number; durationMs?: number; title?: string; artist?: string; album?: string }> }
      const tracks = payload.files.map((item) => ({
        ...createRemoteTrack(item.path, item.size, item.mtime, serverUrl),
        durationMs: item.durationMs ?? 0,
        ...(item.title ? { title: item.title } : {}),
        ...(item.artist ? { artist: item.artist } : {}),
        ...(item.album ? { album: item.album } : {}),
      }))
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
    try {
      setStatus('Загружаю файлы на Orange Pi…')
      const base = serverUrl.replace(/\/$/, '')
      for (const file of [...files]) {
        const response = await uploadAudio(base, uploadDir, file, file.name)
        if (!response.ok) {
          throw new Error(`Ошибка загрузки ${file.name}: ${response.status}`)
        }
      }
      setStatus(`Загружено ${files.length} файлов на Orange Pi.`)
      await refreshRemoteLibrary()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось загрузить файлы')
    }
  }

  async function deleteRemoteTrack(track: MusicTrack) {
    const confirmed = window.confirm(`Переместить «${track.title}» в корзину Orange Pi?`)
    if (!confirmed) return
    setStatus(`Удаляю ${track.title}…`)
    try {
      const base = serverUrl.replace(/\/$/, '')
      const encodedPath = track.relativePath.split('/').map(encodeURIComponent).join('/')
      const response = await authorizedFetch(`${base}/api/file/${encodedPath}`, { method: 'DELETE' })
      if (!response.ok) throw new Error(`Удаление: ${response.status}`)
      const deleted = await response.json() as { trashId?: string }
      setLastDeletedId(deleted.trashId ?? null)
      setQueue((current) => current.filter((item) => item.id !== track.id))
      if (currentId === track.id) {
        setCurrentId(null)
        setIsPlaying(false)
        const audio = audioRef.current
        if (audio) {
          audio.pause()
          audio.removeAttribute('src')
        }
      }
      setStatus(`Удалено: ${track.title}`)
      await refreshRemoteLibrary()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось удалить файл')
    }
  }

  async function undoDelete() {
    if (!lastDeletedId) return
    try {
      const response = await authorizedFetch(`${serverUrl.replace(/\/$/, '')}/api/trash/${lastDeletedId}/restore`, { method: 'POST' })
      if (!response.ok) throw new Error(response.status === 409 ? 'Имя уже занято. Откройте корзину в настройках.' : `Восстановление: ${response.status}`)
      setLastDeletedId(null)
      await refreshRemoteLibrary()
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Не удалось восстановить') }
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
    const pool = shuffle && remoteTracks.length > tracks.length ? remoteTracks : tracks
    setQueue(pool)
    rememberShuffleId(track.id)
    setCurrentId(track.id)
    if (currentId === track.id) setPlaybackRevision((value) => value + 1)
  }

  function rememberShuffleId(trackId: string) {
    const recent = recentShuffleIdsRef.current
    if (recent[recent.length - 1] === trackId) return
    recent.push(trackId)
    if (recent.length > 24) recent.splice(0, recent.length - 24)
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
    if (audio.paused) resumePlayback()
    else audio.pause()
  }

  function resumePlayback() {
    const audio = audioRef.current
    if (!audio || !currentTrack) return
    void audio.play().catch((error: unknown) => {
      setStatus(error instanceof Error ? error.message : 'Не удалось воспроизвести трек')
    })
  }

  function playNext() {
    if (shuffle) {
      const pool = remoteTracks.length ? remoteTracks : queue
      const next = pickWeightedShuffle(pool, behavior, favorites, recentShuffleIdsRef.current.slice(-8))
      if (!next) {
        setCurrentId(null)
        setIsPlaying(false)
        return
      }
      rememberShuffleId(next.id)
      if (!queue.some((track) => track.id === next.id)) {
        setQueue(pool)
      }
      setCurrentId(next.id)
      if (currentId === next.id) setPlaybackRevision((value) => value + 1)
      return
    }
    if (currentIndex < 0 || currentIndex >= queue.length - 1) {
      setCurrentId(null)
      setIsPlaying(false)
      return
    }
    setCurrentId(queue[currentIndex + 1].id)
  }

  function playPrevious() {
    if (shuffle) {
      const recent = recentShuffleIdsRef.current
      const previousId = recent.length >= 2 ? recent[recent.length - 2] : null
      const previous = previousId ? (remoteTracks.length ? remoteTracks : queue).find((track) => track.id === previousId) : null
      if (!previous) return
      maybeRecordSkip()
      recent.pop()
      setCurrentId(previous.id)
      return
    }
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

  const pageMeta = pageTitle(location.pathname)

  return (
    <div className={`app-layout${currentTrack ? ' has-player' : ''}`}>
      <audio ref={audioRef} preload="metadata" crossOrigin="anonymous" />

      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">♪</div>
          <div className="brand-copy">
            <strong>Music Web</strong>
            <span>Orange Pi</span>
          </div>
        </div>

        <nav className="sidebar-nav">
          <NavLink to="/" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
            <span className="nav-icon">🏠</span>
            Главная
          </NavLink>
          <NavLink to="/sync" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
            <span className="nav-icon">☁️</span>
            Синк
          </NavLink>
          <NavLink to="/trim" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
            <span className="nav-icon">✂️</span>
            Редактор
          </NavLink>
          <NavLink to="/settings" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
            <span className="nav-icon">⚙️</span>
            Настройки
          </NavLink>
        </nav>

        <div className="sidebar-stats">
          <div className="mini-stat">
            <span>На сервере</span>
            <strong>{remoteTracks.length}</strong>
          </div>
          <div className="mini-stat">
            <span>Избранное</span>
            <strong>{favorites.size}</strong>
          </div>
          {latestRelease?.apkUrl ? (
            <a className="btn btn-primary apk-promo" href={latestRelease.apkUrl} target="_blank" rel="noreferrer">
              Скачать APK
            </a>
          ) : null}
        </div>
      </aside>

      <div className="main-column">
        <header className="page-header">
          <div>
            <h1>{pageMeta.title}</h1>
            <p>{pageMeta.subtitle}</p>
          </div>
          <div className="header-actions">
            {location.pathname === '/' ? (
              <button type="button" className="btn btn-primary" onClick={() => void refreshRemoteLibrary()}>
                {loadingRemote ? 'Обновление…' : 'Обновить библиотеку'}
              </button>
            ) : null}
            {location.pathname === '/sync' ? (
              <button type="button" className="btn btn-primary" onClick={() => void refreshRemoteLibrary()}>
                {loadingRemote ? 'Обновление…' : 'Обновить библиотеку'}
              </button>
            ) : null}
          </div>
        </header>

        {(location.pathname === '/' || location.pathname === '/sync') && (
          <div className="search-bar">
            <input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="Поиск по названию, исполнителю, альбому…"
            />
            <SortSelect value={sortMode} onChange={setSortMode} />
          </div>
        )}

        <Routes>
          <Route
            path="/"
            element={
              <HomePage
                libraryTracks={visibleTracks}
                sections={sections.map((section) => ({
                  ...section,
                  tracks: filterTracks(section.tracks, searchQuery),
                })).filter((section) => section.tracks.length > 0)}
                libraryTab={libraryTab}
                sortMode={sortMode}
                expandedGroup={expandedGroup}
                currentId={currentId}
                favorites={favorites}
                latestRelease={latestRelease}
                loadingRelease={loadingRelease}
                loadingLibrary={loadingRemote}
                onLibraryTabChange={setLibraryTab}
                onExpandedGroupChange={setExpandedGroup}
                onToggleFavorite={toggleFavorite}
                onPlay={(track, tracks) => playSelection(track, tracks)}
              />
            }
          />
          <Route
            path="/sync"
            element={
              <SyncPage
                serverUrl={serverUrl}
                remoteTracks={visibleTracks}
                sortMode={sortMode}
                loadingRemote={loadingRemote}
                uploadDir={uploadDir}
                onUploadDirChange={setUploadDir}
                onUpload={uploadFiles}
                onPlay={(track, tracks) => playSelection(track, tracks ?? remoteTracks)}
                onToggleFavorite={toggleFavorite}
                onDelete={(track) => void deleteRemoteTrack(track)}
                currentId={currentId}
                favorites={favorites}
                serverOnline={serverOnline}
                onServerOnlineChange={setServerOnline}
              />
            }
          />
          <Route
            path="/trim"
            element={
              <TrimPage
                key={serverUrl}
                serverUrl={serverUrl}
                tracks={remoteTracks}
                loadingLibrary={loadingRemote}
                uploadDir={uploadDir}
                onUploadDirChange={setUploadDir}
                onUploaded={refreshRemoteLibrary}
                onStatus={setStatus}
              />
            }
          />
          <Route
            path="/settings"
            element={
              <SettingsPage
                serverUrl={serverUrl}
                githubRepo={githubRepo}
                latestRelease={latestRelease}
                loudnessNormalize={loudnessNormalize}
                serverOnline={serverOnline}
                onServerUrlChange={setServerUrl}
                onGithubRepoChange={setGithubRepo}
                onLoudnessNormalizeChange={setLoudnessNormalize}
                onServerOnlineChange={setServerOnline}
                onRefresh={refreshRemoteLibrary}
                onRefreshRelease={fetchLatestRelease}
              />
            }
          />
        </Routes>
      </div>

      <nav className="mobile-nav">
        <NavLink to="/" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
          <span className="nav-icon">🏠</span>
          Главная
        </NavLink>
        <NavLink to="/sync" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
          <span className="nav-icon">☁️</span>
          Синк
        </NavLink>
        <NavLink to="/trim" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
          <span className="nav-icon">✂️</span>
          Редактор
        </NavLink>
        <NavLink to="/settings" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
          <span className="nav-icon">⚙️</span>
          Настройки
        </NavLink>
      </nav>

      {status ? <div className="toast">{status} {lastDeletedId && <button type="button" onClick={() => void undoDelete()}>Отменить удаление</button>}</div> : null}

      {currentTrack ? (
        <footer className="player-bar" ref={playerBarRef}>
          <div className="player-inner">
            <div className="player-top">
              <div className="cover-tile">
                <TrackArt track={currentTrack} className="cover-image" />
              </div>
              <div className="player-meta">
                <strong>{currentTrack.title}</strong>
                <span>{currentTrack.artist || 'Неизвестный исполнитель'}</span>
              </div>
              <div className="player-controls">
                <button
                  type="button"
                  className={`player-btn player-btn-icon${shuffle ? ' active' : ''}`}
                  onClick={() => setShuffle((current) => !current)}
                  aria-label="Случайное воспроизведение"
                  aria-pressed={shuffle}
                  title={shuffle ? 'Случайный (рекомендации)' : 'По порядку'}
                >
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="m18 2 4 4-4 4M18 14l4 4-4 4M2 18h2.5C10.5 18 13.5 6 19.5 6H22M2 6h2.5c2.4 0 4.5 2.6 6.4 5.3M14 14.7c1.6 2 3.3 3.3 5.5 3.3H22" />
                  </svg>
                </button>
                <button type="button" className="player-btn player-btn-icon" onClick={playPrevious} aria-label="Назад">
                  ⏮
                </button>
                <button type="button" className="player-btn player-btn-play" onClick={togglePlayback} aria-label="Play/Pause">
                  {isPlaying ? '⏸' : '▶'}
                </button>
                <button
                  type="button"
                  className="player-btn player-btn-icon"
                  aria-label="Далее"
                  onClick={() => {
                    maybeRecordSkip()
                    playNext()
                  }}
                >
                  ⏭
                </button>
              </div>
            </div>
            <div className="seek-wrap">
              <span>{formatDuration(positionMs)}</span>
              <input
                className="seekbar"
                type="range"
                min={0}
                max={Math.max(durationMs, 1)}
                value={Math.min(positionMs, Math.max(durationMs, 1))}
                onChange={seekTo}
              />
              <span>{formatDuration(durationMs)}</span>
            </div>
            <div className="volume-wrap" title="Громкость">
              <span aria-hidden="true">🔊</span>
              <input
                className="volumebar"
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={volume}
                onChange={(event) => setVolume(Number(event.target.value))}
                aria-label="Громкость"
              />
              <span>{Math.round(volume * 100)}%</span>
            </div>
          </div>
        </footer>
      ) : null}
    </div>
  )
}

export default App

function HomePage(props: {
  libraryTracks: MusicTrack[]
  sections: RecommendationSection[]
  libraryTab: LibraryTab
  sortMode: SortMode
  expandedGroup: string | null
  currentId: string | null
  favorites: Set<string>
  latestRelease: ReleaseInfo | null
  loadingRelease: boolean
  loadingLibrary: boolean
  onLibraryTabChange: (tab: LibraryTab) => void
  onExpandedGroupChange: (key: string | null) => void
  onToggleFavorite: (trackId: string) => void
  onPlay: (track: MusicTrack, tracks: MusicTrack[]) => void
}) {
  const {
    libraryTracks,
    sections,
    libraryTab,
    sortMode,
    expandedGroup,
    currentId,
    favorites,
    latestRelease,
    loadingRelease,
    loadingLibrary,
    onLibraryTabChange,
    onExpandedGroupChange,
    onToggleFavorite,
    onPlay,
  } = props

  const favoriteList = useMemo(
    () => favoriteTracks(libraryTracks, favorites),
    [libraryTracks, favorites],
  )
  const artistGroups = useMemo(() => groupTracks(libraryTracks, 'artists', sortMode), [libraryTracks, sortMode])
  const albumGroups = useMemo(() => groupTracks(libraryTracks, 'albums', sortMode), [libraryTracks, sortMode])
  const folderGroups = useMemo(() => groupTracks(libraryTracks, 'folders', sortMode), [libraryTracks, sortMode])

  const hasContent =
    libraryTab === 'recommendations'
      ? sections.length > 0
      : libraryTab === 'tracks'
        ? libraryTracks.length > 0
        : libraryTab === 'favorites'
          ? favoriteList.length > 0
          : libraryTab === 'artists'
            ? artistGroups.length > 0
            : libraryTab === 'albums'
              ? albumGroups.length > 0
              : folderGroups.length > 0

  const activeGroups =
    libraryTab === 'artists' ? artistGroups : libraryTab === 'albums' ? albumGroups : folderGroups

  return (
    <section className="page-grid">
      {latestRelease?.apkUrl ? (
        <article className="panel apk-promo">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Android</p>
              <h2>
                {loadingRelease
                  ? 'Проверяю версию…'
                  : `Версия ${latestRelease.versionName ?? latestRelease.tagName}`}
              </h2>
            </div>
            <a className="btn btn-primary" href={latestRelease.apkUrl} target="_blank" rel="noreferrer">
              Скачать APK
            </a>
          </div>
        </article>
      ) : null}

      <article className="panel library-panel">
        <div className="library-tabs" role="tablist" aria-label="Разделы библиотеки">
          {LIBRARY_TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={libraryTab === tab.id}
              className={`library-tab${libraryTab === tab.id ? ' active' : ''}`}
              onClick={() => {
                onLibraryTabChange(tab.id)
                onExpandedGroupChange(null)
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {loadingLibrary ? (
          <div className="empty-state">
            <div className="empty-icon">⏳</div>
            <p>Загружаю библиотеку с Orange Pi…</p>
          </div>
        ) : null}

        {!hasContent && !loadingLibrary ? (
          <div className="empty-state">
            <div className="empty-icon">🎵</div>
            <p>
              {libraryTab === 'favorites'
                ? 'Пока нет избранных треков. Нажми ♡ у понравившейся песни.'
                : 'На Orange Pi пока нет музыки. Загрузи файлы во вкладке «Синк» или проверь адрес сервера в настройках.'}
            </p>
          </div>
        ) : null}

        {!loadingLibrary && libraryTab === 'recommendations'
          ? sections.map((section) => (
              <div className="library-section" key={section.key}>
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
              </div>
            ))
          : null}

        {!loadingLibrary && libraryTab === 'tracks' ? (
          <div className="list">
            {libraryTracks.map((track) => (
              <TrackRow
                key={track.id}
                track={track}
                sortMode={sortMode}
                active={track.id === currentId}
                favorite={favorites.has(track.id)}
                onPlay={() => onPlay(track, libraryTracks)}
                onToggleFavorite={() => onToggleFavorite(track.id)}
              />
            ))}
          </div>
        ) : null}

        {!loadingLibrary && libraryTab === 'favorites' ? (
          <div className="list">
            {favoriteList.map((track) => (
              <TrackRow
                key={track.id}
                track={track}
                sortMode={sortMode}
                active={track.id === currentId}
                favorite
                onPlay={() => onPlay(track, favoriteList)}
                onToggleFavorite={() => onToggleFavorite(track.id)}
              />
            ))}
          </div>
        ) : null}

        {!loadingLibrary && (libraryTab === 'artists' || libraryTab === 'albums' || libraryTab === 'folders')
          ? activeGroups.map((group) => (
              <div className="group-block" key={group.key}>
                <button
                  type="button"
                  className="group-card"
                  onClick={() => onExpandedGroupChange(expandedGroup === group.key ? null : group.key)}
                >
                  <TrackArt
                    track={group.tracks.find((track) => track.youtubeArtUrl || track.artUrl) ?? group.tracks[0]}
                    className="group-art"
                    fallback={group.label}
                  />
                  <div className="group-copy">
                    <strong>{group.label}</strong>
                    <span>
                      {group.tracks.length} треков
                      {sortMode === 'date_added'
                        ? ` • ${formatRelativeAddedAt(groupNewestAddedAt(group.tracks))}`
                        : ''}
                    </span>
                  </div>
                  <span className="group-chevron">{expandedGroup === group.key ? '▾' : '▸'}</span>
                </button>
                {expandedGroup === group.key ? (
                  <div className="list group-tracks">
                    {group.tracks.map((track) => (
                      <TrackRow
                        key={track.id}
                        track={track}
                        sortMode={sortMode}
                        active={track.id === currentId}
                        favorite={favorites.has(track.id)}
                        onPlay={() => onPlay(track, group.tracks)}
                        onToggleFavorite={() => onToggleFavorite(track.id)}
                      />
                    ))}
                  </div>
                ) : null}
              </div>
            ))
          : null}
      </article>
    </section>
  )
}

function SyncPage(props: {
  serverUrl: string
  remoteTracks: MusicTrack[]
  loadingRemote: boolean
  uploadDir: string
  onUploadDirChange: (value: string) => void
  onUpload: (files: FileList | null) => Promise<void>
  onPlay: (track: MusicTrack, tracks?: MusicTrack[]) => void
  onToggleFavorite: (trackId: string) => void
  onDelete: (track: MusicTrack) => void
  currentId: string | null
  favorites: Set<string>
  sortMode: SortMode
  serverOnline: boolean | null
  onServerOnlineChange: (value: boolean | null) => void
}) {
  const {
    serverUrl,
    remoteTracks,
    loadingRemote,
    uploadDir,
    onUploadDirChange,
    onUpload,
    onPlay,
    onToggleFavorite,
    onDelete,
    currentId,
    favorites,
    sortMode,
    serverOnline,
    onServerOnlineChange,
  } = props

  useEffect(() => {
    let cancelled = false
    async function ping() {
      try {
        const response = await fetch(`${serverUrl.replace(/\/$/, '')}/api/health`)
        if (!cancelled) onServerOnlineChange(response.ok)
      } catch {
        if (!cancelled) onServerOnlineChange(false)
      }
    }
    void ping()
    return () => {
      cancelled = true
    }
  }, [serverUrl, onServerOnlineChange])

  return (
    <section className="page-grid">
      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Orange Pi</p>
            <h2>Удалённая библиотека</h2>
          </div>
          <div className={`server-status${serverOnline === true ? ' online' : serverOnline === false ? ' offline' : ''}`}>
            <span className="server-status-dot" aria-hidden="true" />
            <span>
              {serverOnline === true ? 'Сервер онлайн' : serverOnline === false ? 'Сервер офлайн' : 'Проверка…'}
            </span>
          </div>
        </div>
        <p className="subtitle">
          {loadingRemote ? 'Загружаю список…' : `${remoteTracks.length} треков • ${serverUrl}`}
        </p>
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Загрузка</p>
            <h2>Добавить музыку на Orange Pi</h2>
          </div>
        </div>
        <label className="field">
          Подпапка в /srv/music
          <input
            value={uploadDir}
            onChange={(event) => onUploadDirChange(event.target.value)}
            placeholder="Например: New arrivals/2026"
          />
        </label>
        <label className="upload-zone">
          <strong>Выбери файлы для загрузки</strong>
          <span className="subtitle">MP3, FLAC, OGG, M4A и другие</span>
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
            <p className="eyebrow">Прослушивание</p>
            <h2>Треки на сервере</h2>
          </div>
        </div>
        {!remoteTracks.length && !loadingRemote ? (
          <div className="empty-state">
            <div className="empty-icon">📂</div>
            <p>На Orange Pi пока нет файлов или они не попали в поиск.</p>
          </div>
        ) : (
          <div className="list">
            {remoteTracks.map((track) => (
              <div className="track-row-with-actions" key={track.id}>
                <TrackRow
                  track={track}
                  sortMode={sortMode}
                  active={track.id === currentId}
                  favorite={favorites.has(track.id)}
                  onPlay={() => onPlay(track, remoteTracks)}
                  onToggleFavorite={() => onToggleFavorite(track.id)}
                />
                <TrackActionsMenu track={track} onDelete={() => onDelete(track)} />
              </div>
            ))}
          </div>
        )}
      </article>
    </section>
  )
}

function SettingsPage(props: {
  serverUrl: string
  githubRepo: string
  latestRelease: ReleaseInfo | null
  loudnessNormalize: boolean
  serverOnline: boolean | null
  onServerUrlChange: (value: string) => void
  onGithubRepoChange: (value: string) => void
  onLoudnessNormalizeChange: (value: boolean) => void
  onServerOnlineChange: (value: boolean | null) => void
  onRefresh: () => Promise<void>
  onRefreshRelease: () => Promise<void>
}) {
  const {
    serverUrl,
    githubRepo,
    latestRelease,
    loudnessNormalize,
    serverOnline,
    onServerUrlChange,
    onGithubRepoChange,
    onLoudnessNormalizeChange,
    onServerOnlineChange,
    onRefresh,
    onRefreshRelease,
  } = props
  const hasDirectoryPicker =
    typeof (window as Window & { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function'
  const [tokenDraft, setTokenDraft] = useState(() => ({ base: serverUrl, value: loadAccessToken(serverUrl) }))
  const accessToken = tokenDraft.base === serverUrl ? tokenDraft.value : loadAccessToken(serverUrl)

  useEffect(() => {
    let cancelled = false
    async function ping() {
      onServerOnlineChange(null)
      try {
        const response = await fetch(`${serverUrl.replace(/\/$/, '')}/api/health`)
        if (!cancelled) onServerOnlineChange(response.ok)
      } catch {
        if (!cancelled) onServerOnlineChange(false)
      }
    }
    void ping()
    return () => {
      cancelled = true
    }
  }, [serverUrl, onServerOnlineChange])

  return (
    <section className="page-grid">
      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Подключение</p>
            <h2>Пинг сервера</h2>
          </div>
          <div className={`server-status${serverOnline === true ? ' online' : serverOnline === false ? ' offline' : ''}`}>
            <span className="server-status-dot" aria-hidden="true" />
            <span>
              {serverOnline === true ? 'Онлайн' : serverOnline === false ? 'Офлайн' : 'Проверка…'}
            </span>
          </div>
        </div>
        <label className="field">
          Базовый URL сервера
          <input value={serverUrl} onChange={(event) => onServerUrlChange(event.target.value)} />
        </label>
        <label className="field">
          Ключ доступа сервера
          <input type="password" autoComplete="off" value={accessToken} onChange={event => {
            setTokenDraft({ base: serverUrl, value: event.target.value })
            saveAccessToken(event.target.value, serverUrl)
          }} />
        </label>
        <p className="subtitle" style={{ marginTop: -6, marginBottom: 12 }}>
          Если сайт открыт с Orange Pi (LAN или Tailscale), используется адрес текущей страницы автоматически.
        </p>
        <label className="field">
          GitHub repo (для APK)
          <input
            value={githubRepo}
            onChange={(event) => onGithubRepoChange(event.target.value)}
            placeholder="owner/repo"
          />
        </label>
        <div className="actions">
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              void (async () => {
                onServerOnlineChange(null)
                try {
                  const response = await fetch(`${serverUrl.replace(/\/$/, '')}/api/health`)
                  onServerOnlineChange(response.ok)
                } catch {
                  onServerOnlineChange(false)
                }
                await onRefresh()
              })()
            }}
          >
            Проверить сервер
          </button>
          <button type="button" className="btn" onClick={() => void onRefreshRelease()}>
            Проверить APK
          </button>
        </div>
        {latestRelease?.versionName ? (
          <p style={{ marginTop: 12 }}>
            Последний APK: {latestRelease.versionName} ({latestRelease.versionCode ?? '?'})
          </p>
        ) : null}
      </article>

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Воспроизведение</p>
            <h2>Громкость</h2>
          </div>
        </div>
        <label className="toggle-row">
          <span>
            <strong>Выравнивать громкость</strong>
            <span className="subtitle">Автоматически подстраивает уровень разных треков</span>
          </span>
          <input
            type="checkbox"
            checked={loudnessNormalize}
            onChange={(event) => onLoudnessNormalizeChange(event.target.checked)}
          />
        </label>
      </article>

      <TrashPanel key={serverUrl} serverUrl={serverUrl} onRestored={onRefresh} />

      <article className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Браузер</p>
            <h2>Возможности</h2>
          </div>
        </div>
        <div className="caps">
          <StatCard label="Выбор папки" value={hasDirectoryPicker ? 'Да' : 'Импорт'} />
          <StatCard label="PWA" value="Да" />
          <StatCard label="Upload" value="Да" />
        </div>
      </article>
    </section>
  )
}

function SortSelect(props: { value: SortMode; onChange: (mode: SortMode) => void }) {
  const { value, onChange } = props
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const current = SORT_MODES.find((mode) => mode.id === value) ?? SORT_MODES[0]

  useEffect(() => {
    if (!open) return
    const close = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('click', close)
    return () => document.removeEventListener('click', close)
  }, [open])

  return (
    <div className="sort-control" ref={rootRef}>
      <span>Сортировка</span>
      <button
        type="button"
        className="sort-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{current.label}</span>
        <span className="sort-chevron">{open ? '▴' : '▾'}</span>
      </button>
      {open ? (
        <div className="sort-menu" role="listbox">
          {SORT_MODES.map((mode) => (
            <button
              key={mode.id}
              type="button"
              role="option"
              aria-selected={mode.id === value}
              className={`sort-option${mode.id === value ? ' active' : ''}`}
              onClick={() => {
                onChange(mode.id)
                setOpen(false)
              }}
            >
              {mode.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function TrackActionsMenu(props: { track: MusicTrack; onDelete: () => void }) {
  const { track, onDelete } = props
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!open) return
    const close = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('click', close)
    return () => document.removeEventListener('click', close)
  }, [open])

  return (
    <div className="track-actions" ref={rootRef}>
      <button
        type="button"
        className="secondary-button track-gear"
        aria-label="Действия с треком"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        ⚙
      </button>
      {open ? (
        <div className="track-actions-menu" role="menu">
          <a
            className="track-actions-item"
            role="menuitem"
            href={track.remoteUrl}
            download
            onClick={() => setOpen(false)}
          >
            Скачать
          </a>
          <button
            type="button"
            className="track-actions-item danger"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              onDelete()
            }}
          >
            Удалить
          </button>
        </div>
      ) : null}
    </div>
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
      <div className="artwork">
        <TrackArt track={track} className="artwork-image" />
      </div>
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
  sortMode: SortMode
  active: boolean
  favorite: boolean
  onPlay: () => void
  onToggleFavorite: () => void
}) {
  const { track, sortMode, active, favorite, onPlay, onToggleFavorite } = props
  const trailingMeta =
    sortMode === 'date_added'
      ? formatRelativeAddedAt(track.addedAt)
      : sortMode === 'duration'
        ? formatDuration(track.durationMs)
        : formatDuration(track.durationMs)

  return (
    <div className={`track-row ${active ? 'active' : ''}`}>
      <button type="button" className="track-row-main" onClick={onPlay}>
        <div className="track-avatar">
          <TrackArt track={track} className="track-avatar-image" />
        </div>
        <div className="track-copy">
          <strong>{track.title}</strong>
          <span>
            {track.artist || 'Неизвестный исполнитель'} • {track.album || 'Неизвестный альбом'}
          </span>
        </div>
        <span className="track-meta">{trailingMeta}</span>
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

function pageTitle(pathname: string): { title: string; subtitle: string } {
  switch (pathname) {
    case '/sync':
      return {
        title: 'Синхронизация',
        subtitle: 'Библиотека Orange Pi, проигрывание и загрузка файлов с ПК.',
      }
    case '/trim':
      return {
        title: 'Редактор',
        subtitle: 'Обрезка локального файла и загрузка WAV на Orange Pi.',
      }
    case '/settings':
      return {
        title: 'Настройки',
        subtitle: 'Адрес сервера, громкость и проверка APK-обновлений.',
      }
    default:
      return {
        title: 'Главная',
        subtitle: 'Музыка с Orange Pi, рекомендации и избранное.',
      }
  }
}

function filterTracks(tracks: MusicTrack[], query: string) {
  const needle = query.trim().toLowerCase()
  if (!needle) return tracks
  return tracks.filter(
    (track) =>
      track.title.toLowerCase().includes(needle) ||
      track.artist.toLowerCase().includes(needle) ||
      track.album.toLowerCase().includes(needle) ||
      track.relativePath.toLowerCase().includes(needle),
  )
}

function formatDuration(durationMs: number) {
  if (!Number.isFinite(durationMs) || durationMs <= 0) return '0:00'
  const totalSeconds = Math.floor(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function youtubeArtUrlFromPath(path: string) {
  const matches = [...path.matchAll(/\[(?:-)?([A-Za-z0-9_-]{11})\]/g)]
  const videoId = matches.at(-1)?.[1]
  return videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : undefined
}

function createRemoteTrack(path: string, size: number, mtime: number, baseUrl: string): MusicTrack {
  const parts = path.split('/')
  const fileName = parts.pop() ?? path
  const title = fileName.replace(/\.[^.]+$/, '')
  const album = parts.length > 1 ? parts[parts.length - 1] : ''
  const artist = parts.length > 0 ? parts[0] : ''
  const base = baseUrl.replace(/\/$/, '')
  const encodedPath = path.split('/').map(encodeURIComponent).join('/')
  // Same-origin → relative URLs so Tailscale/LAN switches don't break art/stream.
  const origin = typeof window !== 'undefined' ? window.location.origin.replace(/\/$/, '') : ''
  const prefix = origin && base === origin ? '' : base
  return {
    id: `remote:${path}`,
    title,
    artist,
    album,
    durationMs: 0,
    size,
    addedAt: mtime,
    relativePath: path,
    source: 'remote',
    remoteUrl: `${prefix}/files/${encodedPath}`,
    artUrl: `${prefix}/api/art/${encodedPath}?v=${mtime || size}`,
    youtubeArtUrl: youtubeArtUrlFromPath(path),
  }
}
