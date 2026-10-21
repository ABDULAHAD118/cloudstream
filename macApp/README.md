# CloudStream for Mac (fork-only)

A small Compose Desktop app that runs CloudStream's Android extensions on macOS. It reuses upstream's
`library` module (MainAPI, extractors, plugin API) and the `shared` theme. Everything Mac-specific lives
in this module, so the daily upstream sync stays conflict-free. It's separate from upstream's `desktopApp`,
which is still a placeholder.

## Install

1. From the latest **Mac build** release, download `-intel.dmg` for an Intel Mac or `-apple-silicon.dmg` for an M-series Mac, then drag CloudStream to Applications.
2. The first launch is blocked because the app isn't notarized. Go to System Settings → Privacy & Security → **Open Anyway**.
3. Open **Extensions**. The official repo is already added. Add more from **Community repositories** (the directory
   the Android setup screen links to), or paste a link: a `repo.json` URL, a GitHub project URL, or a `cloudstreamrepo://` link.

App data (repos, installed extensions, converted jars) lives in `~/Library/Application Support/CloudStream`.

## How extensions run

`.cs3` files are Android DEX. When you install one, it's converted to a JVM jar with dex2jar (with names kept
exactly as they are, since Kotlin uses names like `constructor-impl`) and loaded with its own class loader. Android itself isn't there, so the app fills the gap in two ways:

- **Real implementations** of what extensions use for actual work: NewPipeExtractor's downloader (YouTube), `Context.getSharedPreferences` (saved under
  `shared_prefs/`), `android.util.Base64`, `android.net.Uri`, `android.util.Log`, `CommonActivity.showToast`
  (shown in the window), and the `Plugin` base class.
- **Generated stand-ins** for every other Android class an extension references (views, dialogs, fragments,
  animations), built at load time with exactly the members it uses. Calls into them do nothing and return null/0,
  so extensions with Android settings screens (such as CNC Verse) still load and scrape. Stand-ins for the Activity
  classes are Contexts, because extensions cast the Context they're given to `AppCompatActivity`, as on Android.

If an extension still fails, the error appears under **Installed**.

## What works

- Repositories: the official repo is added on first launch, the community directory can be browsed, and you can add, remove, install, update, and remove extensions.
  The extension list filters by type (Movie, Anime, TV Series, …), language, and search, like on Android. SHA-256 hashes are checked the same way the Android app checks them.
- Home page per provider, search across all providers, and title pages for movies, series (by season), anime (dub/sub), and live streams.
  A search box at the top of Home searches every provider. The chosen Home provider and your search results stay
  put when you open a title and come back, and the provider is remembered across launches.
- **Load more** at the end of each Home and Search row fetches the extension's next page, as scrolling does on Android.
  **⟳ Reload** on Home, Search, title pages, Extensions and the player fetches everything again.
- Apple TV-style look: a sidebar (Search, Home, Library, Downloads, Extensions), San Francisco type, a hero carousel
  of featured titles on Home, an **Up Next** row that resumes where you stopped, and poster shelves that lift on hover.
- Title pages show the backdrop or logo, facts, the IMDb rating and Metascore, episode cards with watch progress,
  cast, More Like This, IMDb ratings breakdown and reviews, and an Information section (IMDb's unofficial API, cached 3 days).
- **Library**: bookmark titles as Watching, Plan to watch, Completed, On hold or Dropped, plus watch history (`library.json`).
- The display stays awake while a video plays (`caffeinate`), and can sleep again when it's paused.
- Hindi audio plays by default whenever a video has it: a Hindi track inside the stream (HLS language groups,
  MKV) is picked a moment after it starts, and YouTube's Hindi dub is picked from the extension's audio files.
  Choosing another language under Audio still works.
- Downloads keep only Hindi and English audio (all languages when neither is there) and one English subtitle file (plain English over CC),
  including the ones inside HLS streams, saved as .srt. When the video already has English subtitles inside (MovieBox's MP4s),
  the .srt goes to the app's data folder instead, so the download folder holds just the video.
- Downloads of titles with separate language tracks put Hindi first, so other players start in Hindi too, and the
  app's player shows each track's language name (the merged file itself only says Track 1, Track 2). Files deleted in
  Finder drop off the Downloads list.
- Hovering the seek bar shows the frame at that spot, from a second silent libVLC player that only opens the
  stream on the first hover. The player's highlights follow the accent colour from Settings.
- **Extensions** looks like the reference app: repository cards with icons and extension counts, a page per
  repository with each extension's icon, version, type and author, one search across every repository, the
  installed list with icons and load status, and **Update all**.
- **Settings**: theme and accent color, autoplay next episode, preferred quality, subtitle size, download folder,
  downloads at the same time, connections per download, open the data folder, clear watch history.
- Built-in player (libVLC, bundled in the DMG). Its main bar has play/pause, ±10 s, a Netflix-style seek bar
  (hover for a time preview) and a pop-up volume bar. Everything else is in **⋮** and on **right-click**: speed,
  quality, source, audio, skip intro, reload, download, open in IINA/VLC. It has play/pause, a seek bar, ±10 s, Skip OP (+85 s, like Android),
  0.25–3× speed, volume up to 200 %, fullscreen, and source, subtitle and audio-track menus. It also plays the next episode
  automatically or with **Next**, resumes where you stopped, and switches to the next source when one fails
  or hasn't started within 45 s. **Quality** caps playback at 4K (the default, meaning best available), 1440p, 1080p,
  720p, 480p, 360p, 240p or 144p and is remembered; it applies to the source order and to adaptive HLS/DASH streams.
  HLS lengths come from the playlist, since libVLC miscounts some streams. The engine starts in the background
  at launch, so opening the player doesn't freeze the window.
- Subtitles are drawn by the app (SRT, WebVTT, ASS, plus HLS subtitle tracks), with text size and sync delay,
  and the chosen language carries over to the next episode.
- Every stream goes through a local OkHttp proxy, so libVLC never does network or TLS itself (inside the JVM its
  TLS handshakes failed intermittently with "Interrupted system call"). The proxy sends all extension headers,
  prefetches HLS segments in parallel (some hosts cap each connection far below the bitrate), and fetches large
  files in 8 MB byte ranges (YouTube throttles a single open-ended request).
- Sources that can seek come first: libVLC 3 can't seek WebM video paired with a separate audio track (YouTube's
  VP9, its only 1440p/4K), so those are listed under Source as "no seeking".
- Separate audio tracks from extensions (YouTube serves video-only streams) play alongside the video. The best one
  is picked automatically, and **Audio** switches between them.
  Streams that need extra headers (cookies, Origin, tokens) go through a local proxy that adds them, as ExoPlayer does on Android.
- Keyboard: Space/K play-pause, ←/→ or J/L ±10 s, ↑/↓ volume, M mute, F fullscreen, N next episode, [ ] speed, Esc exit.
- "Open in IINA/VLC" hands the current stream to an external player if you prefer one.
- **Downloads:** ⬇ on movies, episodes and in the player lists every source and quality (each variant of an HLS
  playlist too). Files go to `~/Movies/CloudStream`, with subtitles saved next to them. Data is fetched with OkHttp
  in parallel (byte ranges, or HLS segments via the library's `M3u8Helper2` as on Android), so it's not limited
  to playback speed. A separate audio track (YouTube) is merged into one MKV locally, and DASH goes through libVLC.
  HLS titles whose audio is a separate group (one track per language) download every language into one MKV.
  Dropped connections are retried. Paused downloads, and ones running when the app quits, carry on from where
  they stopped (files by byte range, HLS by segment); an expired link is swapped for a fresh one of the same quality. The **Downloads** tab shows progress, with Pause, Resume, Cancel, Retry, Delete, Show in Finder, and ▶ Play offline in the
  built-in player.

## What's missing (compared with Android)

- Player extras: AniSkip intro/outro timestamps, subtitle styling and sync offset, Picture-in-Picture, DASH streams that need headers.
- Download notifications.
- HLS playlists with tags between `#EXTINF` and the segment URI aren't fully read by the library's parser;
  downloads then fall back to libVLC, which may stop early on them too. Standard playlists are fine.
- Account sync (AniList, MAL, Simkl, Trakt), subtitle search providers, backup and restore.
- Chromecast and FCast. Torrent titles.
- Extension settings screens (they're Android views and do nothing here; defaults apply), plus extensions that need WebView or Cloudflare bypass
  (`WebViewResolver` is a no-op on the JVM) or other un-stubbed Android APIs.
- Short-code repository links (cutt.ly and py.md). Paste the full URL instead.
- Translations and the in-app updater. Updates come from GitHub releases.

## Develop

```bash
./gradlew :macApp:run
```

To build the app and DMG locally, exactly as CI does, run this with a full JDK 17+ that includes `jpackage`
(for example Temurin). It downloads VLC 3.0.24 for this Mac's architecture, checks its hash, and bundles libVLC:

```bash
JAVA_HOME=/path/to/jdk macApp/build-dmg.sh 1.0.0
```

The DMG lands in `macApp/build/`.

`./gradlew :macApp:jvmTest` runs the offline tests. Prefix the command with `CS_SMOKE=1` to also run the
network tests. They use the official repo and drive every screen through the real UI, starting from a clean
profile: repositories, installing and removing extensions, home, search, a movie, and a series in the player
(play/pause, seek, speed, next episode). They save screenshots to `macApp/build/ui-shots`. Set `CS_VLC_LOG=1`
to print libVLC's warnings and errors and the player's source switching, and `CS_PROXY_LOG=1` to print each
request libVLC makes through the proxy. Requests stopped on purpose (leaving a page mid-load, closing the download picker) aren't
logged; `CS_HTTP_LOG=1` shows them again.
