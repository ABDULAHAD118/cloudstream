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

`.cs3` files are Android DEX. When you install one, it's converted to a JVM jar with dex2jar and then loaded
with a `URLClassLoader`. The app stubs the few Android classes that extensions touch while loading
(`android.content.Context`, `android.util.Log`) and provides a desktop `Plugin` base class. If an extension
calls any other Android API, it fails with an error shown under **Installed**.

## What works

- Repositories: the official repo is added on first launch, the community directory can be browsed, and you can add, remove, install, update, and remove extensions.
  The extension list filters by type (Movie, Anime, TV Series, …), language, and search, like on Android. SHA-256 hashes are checked the same way the Android app checks them.
- Home page per provider, search across all providers, and title pages for movies, series (by season), anime (dub/sub), and live streams.
- Built-in player (libVLC, bundled in the DMG). It has play/pause, a seek bar, ±10 s, Skip OP (+85 s, like Android),
  0.25–3× speed, volume up to 200 %, fullscreen, and source, subtitle and audio-track menus. It also plays the next episode
  automatically or with **Next**, resumes where you stopped, and switches to the next source when one fails.
  Streams that need extra headers (cookies, Origin, tokens) go through a local proxy that adds them, as ExoPlayer does on Android.
- Keyboard: Space/K play-pause, ←/→ or J/L ±10 s, ↑/↓ volume, M mute, F fullscreen, N next episode, [ ] speed, Esc exit.
- "Open in IINA/VLC" hands the current stream to an external player if you prefer one.

## What's missing (compared with Android)

- Player extras: AniSkip intro/outro timestamps, subtitle styling and sync offset, Picture-in-Picture, DASH streams that need headers.
- Downloads, library and bookmarks, watch history, and the continue-watching row (resume positions are saved, though).
- Account sync (AniList, MAL, Simkl, Trakt), subtitle search providers, backup and restore.
- Chromecast and FCast. Torrent titles.
- Extension settings screens (they're Android views), plus extensions that need WebView or Cloudflare bypass
  (`WebViewResolver` is a no-op on the JVM) or other un-stubbed Android APIs.
- Short-code repository links (cutt.ly and py.md). Paste the full URL instead.
- Translations, the app's settings, and the in-app updater. Updates come from GitHub releases.

## Develop

```bash
./gradlew :macApp:run
```

`./gradlew :macApp:jvmTest` runs the offline tests. Prefix the command with `CS_SMOKE=1` to also run the
network tests, which install the official repo and drive search → title → links through the real UI. Those
tests save screenshots to `macApp/build/ui-shots`.

The DMG (`:macApp:packageDmg`) needs a full JDK 17+ with `jpackage`. CI builds an Intel and an Apple Silicon app on separate runners, signs them ad-hoc, and makes the DMGs
(`.github/workflows/mac-release.yml`).
