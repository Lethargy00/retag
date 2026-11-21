# Retag: a free, ad-free music tagger for Android

An open-source alternative to AutomaTag. It finds the right tags and cover art for your music files online and writes them into the files themselves.

## What it does

- **Find tags online.** Searches MusicBrainz and the iTunes Store using a song's current tags or, if it has none, its file name (e.g. `01 - Artist - Title.mp3`). You tap the right match.
- **Cover art.** Pick from Cover Art Archive and iTunes results, up to 1200×1200 px, or choose an image from your gallery.
- **Batch auto-tag.** Select many songs (long-press) and tap **Auto-tag**. Confident matches are saved one by one, and uncertain ones are skipped so you can fix them by hand.
- **Find album & match.** Select songs (long-press), tap **Find album** and pick the release. Each song is matched to a track automatically; the screen shows which ones were found, which are unsure and which still need a track (tap to pick one), with a simple before → after for every song (or every changed field). Then write them all in one go.
- **Rename files** with a pattern, by default just `%title%`. Patterns work like Mp3tag: `%title%[ - %year%]` gives `Song - 1999`, and the `[...]` part is left out when a field inside is empty. Fields: `%title% %artist% %albumartist% %album% %year% %track% %disc% %genre%`.
- **Sort into folders** inside your music folder, e.g. `%albumartist%/%album%` gives `Music/Queen/A Night at the Opera/`. Both happen automatically when tags are saved (turn on in Settings), or on demand: select songs, then ⋮ → **Rename & organize**.
- **Sort the list** by date added, title, artist, album or file name.
- **Edit many files at once.** Set album, artist, genre, year and cover on a whole selection, with optional automatic track numbering.
- **Cover thumbnails** in the song list.
- **Formats:** MP3, M4A/M4B, FLAC, OGG Vorbis, Opus, WMA, WAV, AIFF, DSF. Works on internal storage and SD cards.

## Getting the APK (no coding needed)

GitHub builds the app for you for free:

1. Create a GitHub account if you don't have one, then make a new repository (it can be **private**) at https://github.com/new.
2. Upload this project:
   - **With git:** `git init && git add . && git commit -m "Retag" && git branch -M main && git remote add origin https://github.com/YOU/retag.git && git push -u origin main`
   - **In the browser:** on the new repo page click **uploading an existing file** and drag in *everything inside* this folder. On a Mac, press **Cmd+Shift+.** in Finder to show the hidden `.github` folder, which must be included.
3. Open the **Actions** tab. A **Build APK** run starts automatically and takes about 5 minutes.
4. When it's green, open the repo's **Releases** (right-hand side of the main page). Download **Retag.apk** on your phone and open it. Android asks you to allow installing from your browser the first time.
5. Open Retag and choose your music folder (for example **Music**). The app only sees and changes files inside that folder. Android doesn't allow picking the whole storage or the Download folder.

Every later push to `main` builds a new release (other branches only build). Updates install over the old version because each build is signed with the same key (`app/retag.keystore`). That key sits in this repo, so keep the repo private if you don't want others able to sign "updates" for your copy.

## Building on a computer instead

Open the folder in Android Studio and press Run. From a terminal: `./gradlew assembleRelease`.

## Notes

- The data sources are free community/public APIs: MusicBrainz (about 1 request per second) and iTunes Search (about 20 per minute). Batch tagging therefore takes a few seconds per song.
- "Date added" is when Retag first saw a file (taken from the file's modified date at that point), so renaming or retagging doesn't change it.
- Tags are written to a temporary copy and then copied back into the file, because Android's folder access doesn't give apps a normal file path.
- After saving, the app asks Android to rescan the file so other music players see the new tags. Some players cache tags and need their own rescan.
- Batch mode never guesses: a match below about 75% confidence (90% when the file has no artist) is skipped rather than written.
- Opus tags are read and written by Retag's own code (`OpusTags.kt`), since jaudiotagger doesn't support Opus.
- Tag writing uses [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (LGPL).
