export type TrackSource = 'local' | 'remote'

export type MusicTrack = {
  id: string
  title: string
  artist: string
  album: string
  durationMs: number
  size: number
  relativePath: string
  source: TrackSource
  artUrl?: string
  objectUrl?: string
  remoteUrl?: string
  file?: File
}

export type ListeningBehavior = {
  playCount: number
  completionCount: number
  skipCount: number
  totalListenedMs: number
  lastPlayedAt: number
}

export type RecommendationSection = {
  key: string
  title: string
  subtitle: string
  tracks: MusicTrack[]
}

export type ReleaseInfo = {
  repo: string
  tagName: string
  releaseName: string
  versionName: string | null
  versionCode: number | null
  publishedAt: string | null
  body: string
  apkUrl: string | null
  apkFileName: string | null
  htmlUrl: string | null
}
