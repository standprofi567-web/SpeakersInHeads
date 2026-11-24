# Third-party notices

SpeakersInHeads is licensed under [MIT](LICENSE). The runtime JAR carries the following separately licensed components under `META-INF/jars/`. Fabric Loom adds metadata to the nested JARs for runtime loading. Their decoder classes remain separate from SpeakersInHeads.

| Component | Version | Declared license | Source archive |
| --- | --- | --- | --- |
| Java Audio Stack MP3SPI | 2024.04.19 | LGPL-2.1 | [Maven Central](https://repo.maven.apache.org/maven2/dev/mccue/mp3spi/2024.04.19/mp3spi-2024.04.19-sources.jar) |
| JLayer Decoder | 2024.04.19 | LGPL-2.1 | [Maven Central](https://repo.maven.apache.org/maven2/dev/mccue/jlayer-decoder/2024.04.19/jlayer-decoder-2024.04.19-sources.jar) |
| Tritonus Share | 2024.04.19 | LGPL-2.1 | [Maven Central](https://repo.maven.apache.org/maven2/dev/mccue/tritonus-share/2024.04.19/tritonus-share-2024.04.19-sources.jar) |

[Java Audio Stack upstream repository](https://github.com/bowbahdoe/java-audio-stack) · [GNU LGPL 2.1 text](docs/licenses/LGPL-2.1.txt)

The three components above are used for MP3 decoding through Java Sound. See their upstream source and copyright notices. See the LGPL text for terms that apply to these components.

## Separate platform and development dependencies

| Component | Used as | Licensing reference |
| --- | --- | --- |
| Minecraft 26.3 and its development names | Game and development inputs resolved separately by Loom | [Minecraft EULA](https://www.minecraft.net/en-us/eula) |
| Fabric Loader 0.19.5 build default, 0.20.1 validation | External loader | [Apache-2.0](https://github.com/FabricMC/fabric-loader/blob/master/LICENSE) |
| Fabric API 0.166.2+26.3 | External Fabric mod | [Apache-2.0](https://github.com/FabricMC/fabric/blob/HEAD/LICENSE) |
| Fabric Loom 1.18.4 | Development plugin | [MIT](https://github.com/FabricMC/fabric-loom/blob/HEAD/LICENSE) |
| LWJGL 3.4.3, including STB and OpenAL bindings | Game-provided libraries. STB supports Ogg Vorbis decoding | [BSD-3-Clause](https://github.com/LWJGL/lwjgl3/blob/HEAD/LICENSE.md) |
| OpenAL Soft | Native audio implementation supplied by the game’s LWJGL distribution | [Upstream license](https://github.com/kcat/openal-soft/blob/HEAD/COPYING) |
| Sound Physics Remastered | Optional external mod | [Upstream project](https://github.com/henkelmax/sound-physics-remastered) |
| JUnit 5.13.4 | Test only | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r5.13.4/LICENSE.md) |

The SpeakersInHeads banner is original project artwork.
