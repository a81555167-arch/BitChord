# Fta: Spotify-connected library client (prototype)

This branch is an isolated prototype line and does not change `main` or the existing Fta/BitChord work.

## Product direction

- Keep the existing YouTube Music-backed playback engine as a separate playback subsystem.
- Explore an account-connected library view using supported Spotify APIs and OAuth.
- Prioritize account identity, profile image, the user's playlists, and Home/Explore metadata only where the official API exposes those fields and the app is authorized to use them.
- Cache metadata with explicit expiry and refresh only when needed.
- Keep playback state, Spotify account data, and YouTube Music catalog/search state separate in the architecture.

## Important platform boundary

Spotify's current Developer Policy prohibits applications that integrate Spotify streams/content with content from another service, and prohibits mimicking or replacing Spotify's core user experience without prior written permission. Therefore, this prototype must not automatically map Spotify Home/playlist items to YouTube Music streams or present itself as a replacement Spotify client. Before implementing any cross-service flow, obtain written permission or redesign it as a compliant, clearly separate experience.

Spotify Development Mode access is also restricted and may require a Premium developer account, with limited authorized users and endpoint availability. Do not assume Home/Explore feeds, listening activity, followed artists, or playlist writes are available. Check the current official API documentation and handle unavailable endpoints gracefully.

## First milestones

1. Establish an independent repository for this prototype. This branch is only an interim isolation point until GitHub's repository-creation/import flow is used; it is not a second GitHub fork.
2. Audit the existing YouTube Music playback module and isolate it behind a playback interface.
3. Implement supported Spotify OAuth and `/me` profile retrieval with least-privilege scopes.
4. Verify which playlist and discovery endpoints are actually available for the developer app, then implement only those fields.
5. Add metadata caching, loading/error states, and tests before UI work.
6. Build a separate debug APK and verify its application ID does not conflict with the existing Fta build.

## References

- Spotify Developer Policy: https://developer.spotify.com/policy
- Spotify Web API: https://developer.spotify.com/documentation/web-api
- Spotify developer access update (2026): https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security
