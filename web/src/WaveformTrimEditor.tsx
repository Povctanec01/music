import { useCallback, useEffect, useRef, useState } from 'react'
import { decodeWaveformPeaks, formatTimePrecise, parseTimePrecise } from './waveform'

type DragTarget = 'start' | 'end' | 'playhead' | null

type Props = {
  audioUrl: string
  fileName: string
  duration: number
  startSec: number
  endSec: number
  busy?: boolean
  onStartChange: (value: number) => void
  onEndChange: (value: number) => void
  onDurationChange?: (value: number) => void
  onReset: () => void
  onClose: () => void
  onSave: () => void
}

const HANDLE_HIT_PX = 14

export function WaveformTrimEditor(props: Props) {
  const {
    audioUrl,
    fileName,
    duration,
    startSec,
    endSec,
    busy = false,
    onStartChange,
    onEndChange,
    onDurationChange,
    onReset,
    onClose,
    onSave,
  } = props

  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const peaksRef = useRef<number[]>([])
  const dragRef = useRef<DragTarget>(null)

  const [peaks, setPeaks] = useState<number[]>([])
  const [loadingWave, setLoadingWave] = useState(true)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [startInput, setStartInput] = useState(formatTimePrecise(startSec))
  const [endInput, setEndInput] = useState(formatTimePrecise(endSec))

  useEffect(() => {
    setStartInput(formatTimePrecise(startSec))
  }, [startSec])

  useEffect(() => {
    setEndInput(formatTimePrecise(endSec))
  }, [endSec])

  useEffect(() => {
    let cancelled = false
    setLoadingWave(true)
    setPeaks([])
    peaksRef.current = []

    void (async () => {
      try {
        const response = await fetch(audioUrl)
        if (!response.ok) throw new Error(String(response.status))
        const blob = await response.blob()
        const nextPeaks = await decodeWaveformPeaks(blob)
        if (cancelled) return
        peaksRef.current = nextPeaks
        setPeaks(nextPeaks)
      } catch {
        if (!cancelled) {
          peaksRef.current = []
          setPeaks([])
        }
      } finally {
        if (!cancelled) setLoadingWave(false)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [audioUrl])

  const timeToX = useCallback(
    (sec: number, width: number) => {
      if (duration <= 0) return 0
      return (sec / duration) * width
    },
    [duration],
  )

  const xToTime = useCallback(
    (x: number, width: number) => {
      if (width <= 0 || duration <= 0) return 0
      return (x / width) * duration
    },
    [duration],
  )

  const draw = useCallback(() => {
    const canvas = canvasRef.current
    const wrap = wrapRef.current
    if (!canvas || !wrap) return

    const rect = wrap.getBoundingClientRect()
    const dpr = window.devicePixelRatio || 1
    const width = Math.max(1, Math.floor(rect.width))
    const height = Math.max(1, Math.floor(rect.height))

    canvas.width = Math.floor(width * dpr)
    canvas.height = Math.floor(height * dpr)
    canvas.style.width = `${width}px`
    canvas.style.height = `${height}px`

    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)

    const padY = 18
    const midY = height / 2
    const maxBarH = height / 2 - padY
    const barW = peaksRef.current.length > 0 ? width / peaksRef.current.length : 1
    const startX = timeToX(startSec, width)
    const endX = timeToX(endSec, width)
    const playX = timeToX(currentTime, width)

    const drawWave = (alpha: number, clipStart: number, clipEnd: number) => {
      ctx.save()
      ctx.beginPath()
      ctx.rect(clipStart, 0, clipEnd - clipStart, height)
      ctx.clip()
      ctx.globalAlpha = alpha
      ctx.fillStyle = '#39e06a'
      for (let i = 0; i < peaksRef.current.length; i += 1) {
        const x = i * barW
        const h = peaksRef.current[i] * maxBarH
        ctx.fillRect(x, midY - h, Math.max(1, barW), h * 2)
      }
      ctx.restore()
    }

    drawWave(0.28, 0, width)
    drawWave(1, startX, endX)

    ctx.fillStyle = 'rgba(0, 0, 0, 0.45)'
    ctx.fillRect(0, 0, startX, height)
    ctx.fillRect(endX, 0, width - endX, height)

    const drawHandle = (x: number) => {
      ctx.fillStyle = '#2de2e6'
      ctx.fillRect(x - 3, 8, 6, height - 16)
      ctx.fillStyle = '#ffffff'
      for (let i = -1; i <= 1; i += 1) {
        ctx.fillRect(x - 1, midY + i * 8 - 2, 2, 4)
      }
    }

    drawHandle(startX)
    drawHandle(endX)

    ctx.strokeStyle = '#ffffff'
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(playX, 10)
    ctx.lineTo(playX, height - 10)
    ctx.stroke()

    ctx.fillStyle = '#ffffff'
    ctx.beginPath()
    ctx.roundRect(playX - 28, 2, 56, 22, 11)
    ctx.fill()
    ctx.fillStyle = '#0b1a33'
    ctx.font = '600 11px system-ui, sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(formatTimePrecise(currentTime), playX, 13)
  }, [currentTime, endSec, startSec, timeToX])

  useEffect(() => {
    draw()
  }, [draw, peaks, loadingWave])

  useEffect(() => {
    const onResize = () => draw()
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [draw])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    const onTime = () => {
      setCurrentTime(audio.currentTime)
      if (audio.currentTime >= endSec - 0.02) {
        audio.pause()
        setPlaying(false)
      }
    }
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onMeta = () => {
      const next = audio.duration
      if (Number.isFinite(next) && next > 0) onDurationChange?.(next)
    }

    audio.addEventListener('timeupdate', onTime)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('loadedmetadata', onMeta)
    return () => {
      audio.removeEventListener('timeupdate', onTime)
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('loadedmetadata', onMeta)
    }
  }, [endSec, onDurationChange])

  function pickDragTarget(clientX: number): DragTarget {
    const wrap = wrapRef.current
    if (!wrap || duration <= 0) return null
    const rect = wrap.getBoundingClientRect()
    const x = clientX - rect.left
    const width = rect.width
    const startX = timeToX(startSec, width)
    const endX = timeToX(endSec, width)
    const playX = timeToX(currentTime, width)

    if (Math.abs(x - startX) <= HANDLE_HIT_PX) return 'start'
    if (Math.abs(x - endX) <= HANDLE_HIT_PX) return 'end'
    if (Math.abs(x - playX) <= HANDLE_HIT_PX) return 'playhead'
    return null
  }

  function applyDrag(clientX: number, target: DragTarget) {
    const wrap = wrapRef.current
    if (!wrap || !target || duration <= 0) return
    const rect = wrap.getBoundingClientRect()
    const x = Math.max(0, Math.min(rect.width, clientX - rect.left))
    const sec = xToTime(x, rect.width)

    if (target === 'start') {
      onStartChange(Math.min(sec, endSec - 0.05))
    } else if (target === 'end') {
      onEndChange(Math.max(sec, startSec + 0.05))
    } else if (target === 'playhead') {
      const audio = audioRef.current
      const next = Math.max(startSec, Math.min(endSec, sec))
      if (audio) audio.currentTime = next
      setCurrentTime(next)
    }
  }

  function onPointerDown(event: React.PointerEvent<HTMLDivElement>) {
    if (busy) return
    const target = pickDragTarget(event.clientX)
    if (!target) return
    dragRef.current = target
    event.currentTarget.setPointerCapture(event.pointerId)
    applyDrag(event.clientX, target)
  }

  function onPointerMove(event: React.PointerEvent<HTMLDivElement>) {
    if (!dragRef.current) return
    applyDrag(event.clientX, dragRef.current)
  }

  function onPointerUp(event: React.PointerEvent<HTMLDivElement>) {
    if (!dragRef.current) return
    dragRef.current = null
    event.currentTarget.releasePointerCapture(event.pointerId)
  }

  function togglePlay() {
    const audio = audioRef.current
    if (!audio) return
    if (playing) {
      audio.pause()
      return
    }
    if (audio.currentTime < startSec || audio.currentTime >= endSec) {
      audio.currentTime = startSec
    }
    void audio.play()
  }

  function commitStartInput() {
    const parsed = parseTimePrecise(startInput)
    if (parsed == null) {
      setStartInput(formatTimePrecise(startSec))
      return
    }
    onStartChange(Math.min(parsed, endSec - 0.05))
  }

  function commitEndInput() {
    const parsed = parseTimePrecise(endInput)
    if (parsed == null) {
      setEndInput(formatTimePrecise(endSec))
      return
    }
    onEndChange(Math.max(parsed, startSec + 0.05))
  }

  return (
    <div className="trim-editor">
      <audio ref={audioRef} src={audioUrl} preload="metadata" crossOrigin="anonymous" />

      <div className="trim-editor-toolbar">
        <div className="trim-editor-tabs">
          <button type="button" className="trim-tab active" aria-current="page">
            <span className="trim-tab-icon">✂</span>
            Обрезка
          </button>
          <button type="button" className="trim-tab" disabled title="Скоро">
            🔊
          </button>
          <button type="button" className="trim-tab" disabled title="Скоро">
            ⏱
          </button>
          <button type="button" className="trim-tab" disabled title="Скоро">
            〰
          </button>
        </div>
        <div className="trim-editor-toolbar-actions">
          <button type="button" className="trim-reset-btn" onClick={onReset} disabled={busy}>
            ↺ Сбросить
          </button>
          <button type="button" className="trim-close-btn" onClick={onClose} disabled={busy} aria-label="Закрыть">
            ×
          </button>
        </div>
      </div>

      <div
        ref={wrapRef}
        className={`trim-editor-wave${loadingWave ? ' loading' : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <div className="trim-editor-filename">{fileName}</div>
        <canvas ref={canvasRef} className="trim-editor-canvas" />
        <div className="trim-editor-time start">{formatTimePrecise(startSec)}</div>
        <div className="trim-editor-time end">{formatTimePrecise(endSec)}</div>
        <div className="trim-editor-duration">{formatTimePrecise(duration)}</div>
      </div>

      <div className="trim-editor-footer">
        <div className="trim-editor-footer-left">
          <button type="button" className="trim-play-btn" onClick={togglePlay} disabled={busy || loadingWave}>
            {playing ? '⏸' : '▶'}
          </button>
          <div className="trim-time-fields">
            <label className="trim-time-field">
              <input
                value={startInput}
                onChange={(event) => setStartInput(event.target.value)}
                onBlur={commitStartInput}
                onKeyDown={(event) => event.key === 'Enter' && commitStartInput()}
                disabled={busy}
              />
            </label>
            <label className="trim-time-field">
              <input
                value={endInput}
                onChange={(event) => setEndInput(event.target.value)}
                onBlur={commitEndInput}
                onKeyDown={(event) => event.key === 'Enter' && commitEndInput()}
                disabled={busy}
              />
            </label>
          </div>
        </div>
        <div className="trim-editor-footer-right">
          <div className="trim-format-pill">wav</div>
          <button type="button" className="btn btn-primary trim-save-btn" onClick={onSave} disabled={busy || endSec <= startSec}>
            {busy ? 'Сохранение…' : 'Сохранить'}
          </button>
        </div>
      </div>
    </div>
  )
}
