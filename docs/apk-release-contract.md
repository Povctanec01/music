# APK Release Contract

The website and Android app now read the latest APK metadata from `GET /api/releases/latest?repo=owner/repo`.

## GitHub release requirements

- Repository must be public.
- The latest release must include one `.apk` asset.
- The release tag must use the format `v<versionName>+<versionCode>`.

Example:

- `v1.0+1`
- `v1.1.0+2`

## Normalized metadata

The Orange Pi server returns:

- `repo`
- `tagName`
- `releaseName`
- `versionName`
- `versionCode`
- `publishedAt`
- `body`
- `apkUrl`
- `apkFileName`
- `htmlUrl`

## Notes

- `versionCode` is parsed from the numeric part after `+` in the tag.
- Android update checks require a valid `versionCode`; otherwise the app can only report that the release metadata is incomplete.
- The installed app and downloaded APK must be signed with the same key for update-in-place to succeed.
