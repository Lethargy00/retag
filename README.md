# Retag: a free, ad-free music tagger for Android

An open-source alternative to AutomaTag. It finds the right tags, cover art and lyrics for your music files online and writes them into the files themselves.

## What it does

- **Find tags online.** Searches MusicBrainz and the iTunes Store using a song's current tags or, if it has none, its file name (e.g. `01 - Artist - Title.mp3`). You tap the right match.
- **Cover art.** Pick from Cover Art Archive and iTunes results, up to 1200×1200 px, or choose an image from your gallery.
- **Lyrics.** Plain or time-synced (LRC) lyrics from LRCLIB, embedded in the file.
- **Batch auto-tag.** Select many songs (long-press) and tap **Auto-tag**. Confident matches are saved one by one, and uncertain ones are skipped so you can fix them by hand.
- **Tag a whole album.** In the **Folders** tab tap **Tag album**, or select songs and tap **As album**. Choose the release, check how files were matched to tracks, then write them all in one go.
- **Edit many files at once.** Set album, artist, genre, year and cover on a whole selection, with optional automatic track numbering.
- **Formats:** MP3, M4A/M4B, FLAC, OGG Vorbis, WMA, WAV, AIFF, DSF. Works on internal storage and SD cards.

## Getting the APK (no coding needed)

GitHub builds the app for you for free:

1. Create a GitHub account if you don't have one, then make a new repository (it can be **private**) at https://github.com/new.
2. Upload this project:
   - **With git:** `git init && git add . && git commit -m "Retag" && git branch -M main && git remote add origin https://github.com/YOU/retag.git && git push -u origin main`
   - **In the browser:** on the new repo page click **uploading an existing file** and drag in *everything inside* this folder. On a Mac, press **Cmd+Shift+.** in Finder to show the hidden `.github` folder, which must be included.
3. Open the **Actions** tab. A **Build APK** run starts automatically and takes about 5 minutes.
4. When it's green, open the repo's **Releases** (right-hand side of the main page). Download **Retag.apk** on your phone and open it. Android asks you to allow installing from your browser the first time.
5. Open Retag and grant **"Allow access to manage all files"**. The app needs this to rewrite tags inside your music files.

Every later push builds a new release. Updates install over the old version because each build is signed with the same key (`app/retag.keystore`). That key sits in this repo, so keep the repo private if you don't want others able to sign "updates" for your copy.

## Building on a computer instead

Open the folder in Android Studio and press Run. From a terminal: `./gradlew assembleRelease`.

## Notes

- The data sources are free community/public APIs: MusicBrainz (about 1 request per second), iTunes Search (about 20 per minute) and LRCLIB. Batch tagging therefore takes a few seconds per song.
- After saving, the app asks Android to rescan the file so other music players see the new tags. Some players cache tags and need their own rescan.
- Batch mode never guesses: a match below about 75% confidence (90% when the file has no artist) is skipped rather than written.
- Tag writing uses [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (LGPL).
