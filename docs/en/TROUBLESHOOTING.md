# Troubleshooting

| Symptom | What to check |
| --- | --- |
| No menu | Use Shift + right-click on a player head or wall-mounted player head. Check the loaded mods list in the client log. |
| No sound | Check SIH volume, group or pair volume, Minecraft sound category and distance. Compare with a single head playing the same file. |
| Music missing | Use Open Music Folder or Copy Path to check the active library. Wait for copying and use Refresh Library. Ogg Opus is unsupported. |
| Cannot open the music folder | Use Copy Path and paste it in your file manager. Read the latest log at the timestamp of the attempt for the cause. |
| Spotify has no download | Try Find Local or Import Local File. Spotify track pages supply available metadata only. |
| Group sounds out of time | Note the source count, distance, chunk unload, any Pause or Sync Now action, and the timestamp. Check the same setup without changing volume or spatial mode. |

Logs are under `logs` in the active game directory. For launch or native crashes, check `crash-reports` and `hs_err_pid*.log` as well. Include affected versions and steps. Remove private paths, accounts, server addresses and credentials before sharing.
