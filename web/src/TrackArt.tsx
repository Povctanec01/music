import { useEffect, useState } from 'react'
import type { MusicTrack } from './types'

type Props = {
  track: Pick<MusicTrack, 'id' | 'title' | 'artUrl' | 'youtubeArtUrl'>
  className?: string
  fallback?: string
}

export function TrackArt(props: Props) {
  const { track, className, fallback } = props
  const sources = [track.youtubeArtUrl, track.artUrl].filter(Boolean) as string[]
  const [sourceIndex, setSourceIndex] = useState(0)
  const label = (fallback ?? track.title[0] ?? '♪').toUpperCase()

  useEffect(() => {
    setSourceIndex(0)
  }, [track.id, track.artUrl, track.youtubeArtUrl])

  const src = sources[sourceIndex]
  if (src) {
    return (
      <img
        src={src}
        alt=""
        className={className}
        loading="eager"
        referrerPolicy="no-referrer"
        onError={() => setSourceIndex((current) => current + 1)}
      />
    )
  }

  return <span className={className}>{label}</span>
}
