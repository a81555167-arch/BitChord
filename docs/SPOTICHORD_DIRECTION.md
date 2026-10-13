# SpotiChord: project direction

This branch is the isolated development line for the Spotify-discovery-focused SpotiChord variant. It must not be merged into the original SpotiChord branch by default.

## Product boundary

- Do not use Spotify as an audio playback/streaming provider.
- Reuse BitChord's existing catalogue/search/source pipeline to resolve playable tracks.
- Spotify is a discovery and library metadata source: account library, Liked Songs, playlists, and the visible Home/Explore feed.
- Keep this app installable beside the original SpotiChord build with its own application ID and display name.
- Keep credentials out of source control. Session tokens stay in app-private storage and should never be written to logs.

## Implementation sequence

1. Preserve and verify the current Spotify playlist and Liked Songs sync.
2. Add typed models for Spotify Home/Explore sections and items, with account-scoped persistent caching and explicit stale/error states.
3. Implement the feed client using the same Spotify web-player/Pathfinder source already used by `SpotifyLibrary`; keep requests and parsing isolated from playback.
4. Map feed items into the existing BitChord search/source resolver, so a tap plays through the configured non-Spotify source.
5. Add Home and Explore presentation backed by the new models, with refresh, offline cache, pagination where the source supports it, and graceful handling of expired sessions.
6. Add tests for payload parsing, pagination, caching, and source-resolution boundaries before producing an APK.

## Reliability constraints

Spotify's internal web-player query names and persisted-query hashes are not a stable public API. Verify the exact responses used by the current source before relying on them, keep parsing defensive, and treat authentication/rate-limit failures as errors rather than as an empty feed. If Spotify changes its web client, the library and existing source playback must remain usable.
