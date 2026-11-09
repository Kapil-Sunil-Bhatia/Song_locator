package com.songlocator.app;

/** One audio file from the phone's media library. */
class Song {
    long id;
    String title = "";
    String artist = "";
    String album = "";
    String fileName = "";
    /** Folder shown to the user, e.g. "Phone storage/Music/Old Favourites". */
    String folder = "";
    /** Real path on the phone, e.g. /storage/emulated/0/Music/Old Favourites/Song.mp3 */
    String fullPath = "";
    /** Folder + file name as displayed in the list. */
    String pathShown = "";
    /** Normalised song name, used to spot the same song in several folders. */
    String key = "";
    /** Lower-case searchable text. */
    String hay = "";
    long size;
    long durationMs;
}
