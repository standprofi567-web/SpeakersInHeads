# Installation

Use Minecraft Java Edition 26.3 and Java 25. Install [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API for 26.3](https://modrinth.com/mod/fabric-api) in the same launcher profile. The tested Loader is 0.20.1.

1. Download `speakersinheads-0.8.0-beta.jar` from [GitHub Releases](../../../../releases).
2. Close Minecraft. Open your launcher's game directory for the active profile and locate `mods`.
3. If upgrading, save the old SpeakersInHeads JAR outside `mods`. Leave its other files and the rest of the profile in place.
4. Copy the runtime JAR into `mods`. Install Fabric API there as well. The sources JAR is for development.
5. Start your existing profile and use **Shift + right-click** on a player head or wall-mounted player head.

To verify the download in Windows PowerShell, run `Get-FileHash .\speakersinheads-0.8.0-beta.jar -Algorithm SHA256` and compare the value with `SHA256SUMS.txt` in the same release. To roll back, close Minecraft and restore your saved runtime JAR. Keep only one SpeakersInHeads JAR in `mods`.

See [compatibility](COMPATIBILITY.md) for Sound Physics Remastered.
