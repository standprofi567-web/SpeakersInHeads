# User guide

## Music library

Open **Music Mixer → Manage Library → Open Music Folder**. Add MP3, Ogg Vorbis or supported PCM WAV files to `music/speakersinheads` under the active game directory. Wait for copying to complete, then click **Refresh Library** if the tracks have not appeared. **Import File** copies a file from another folder. **Copy Path** works when the file manager cannot be opened.

## Playback and room setup

- Select a track and click **Play**. Use Pause and Resume to keep your position. The timeline seeks, and Repeat and Shuffle control track order.
- Open group controls from the mixer. Select the other heads you want in the same group and adjust **Group Volume**. **Sync Now** lets you request a correction while the group plays.
- In stereo pair setup, select separate positions for the left and right channels of a stereo recording. Adjust **Pair Volume** in its mixer.
- In **Settings → Spatial Audio**, adjust maximum distance and the full-volume distance independently. Check occlusion and underwater strengths under **Acoustics**. Enable SPR separately if you want its environmental effects.
- Adjust the Minecraft sound category, SIH volume and output boost separately. Lower boost if loud tracks clip. **Appearance** controls the theme and UI animations.

## Smart Music Import

Paste a supported track page or direct audio URL and inspect it. **Metadata Found** describes the track information. **Audio Available** indicates an audio file open to import. Use **Find Local** to look for existing matches or **Import Local File** to choose your own copy. **Download & Import** appears for an authorized direct audio file or an eligible Jamendo download. Import refreshes the managed library.

Spotify may provide the track title and artwork through oEmbed. Artist data can be unavailable. The optional official Spotify Web API path uses a separately configured `SIH_SPOTIFY_ACCESS_TOKEN`. Spotify metadata does not grant access to audio.
