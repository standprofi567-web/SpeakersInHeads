# Compatibility

| Component | Version or behavior |
| --- | --- |
| Minecraft | Java Edition 26.3 |
| Java | 25 |
| Fabric Loader | Metadata declares 0.19.5 or newer. Release validation uses 0.20.1. |
| Fabric API | Required. Release validation uses 0.166.2+26.3. |
| Sound Physics Remastered | Optional. The local reference profile uses 1.5.4+26.3. |
| Servers | The mod is client-side. Paper and Aternos do not receive audio, track selection or playback synchronization from SIH. |

SpeakersInHeads handles its own distance attenuation and can apply wall and underwater filtering. With SPR present, the integration supports SPR environmental effects. Audible balance depends on source layout, listener position and environment. Automated loopback covers an OpenAL path. Human listening and modpack-specific checks use an existing Minecraft profile.
