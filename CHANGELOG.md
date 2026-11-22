# Changelog

What changed in each version of Retag. The section for a version is used as the description of its GitHub release.

## v1.8.0

**Faster start-up, especially with big libraries**
- The song list from last time shows up straight away when you open the app. New or changed files are picked up in the background.
- Tag reading on the first scan now saves its progress every few seconds. Before, closing the app halfway meant starting over from the first song.
- Tags are read from 4 files at a time instead of one by one.
- Cover thumbnails are kept, and only made again for files that actually changed.
- Songs you tag in Retag are no longer read again the next time you open the app.
- Covers that were already loaded once appear faster while scrolling.
- Sorting the list is a little lighter.

**Find album**
- The selected files are read 3 at a time when the screen opens.
- Saving reuses those tags instead of reading every file again, as long as the file hasn't changed in between.

**Releases**
- Releases are now tagged by version (v1.8.0), and the download is named with the version (Retag-1.8.0.apk). The installed app is still called Retag.

Note: the first start after updating reads all songs once more, because older versions didn't save file names. After that, start-up is quick.

## v1.7.0

- The album screen's Files & folders preview uses folder and music-note icons.
- The preview shows which folders are new and which already exist, lists files already in an existing folder, and shows the " (2)" that will be added when a name is already taken.

## v1.6.0

- "Replace all existing tags" now really removes all old tag data before writing: comments, lyrics, fields Retag doesn't show, and the old ID3v1 tag in MP3s.
- The same happens in the editor after applying an online match.

## v1.5.0

- Files & folders preview on the album screen, with switches for renaming and Artist/Album folders.
- Problems with renaming or moving a file are now shown on each song.

## v1.4.0

- Removed the Folders tab. Find album is now the main action.
- New match screen: songs are grouped as Need a track, Check these, Set by you and Matched automatically, and each shows Before → After.

## v1.3.0

- Opus support.
- Cover thumbnails in the song list.
- The default file name pattern is now just the title.

## v1.2.0

- Folder picker: choose your music folder.
- Sorting the song list.
- Renaming files and Artist/Album folders.
- Settings screen.

## v1.1.0

- Removed online lyrics lookup. Lyrics can still be edited by hand.

## v1.0.0

- First release.
