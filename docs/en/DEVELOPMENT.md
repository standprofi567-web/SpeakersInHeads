# Development

Install JDK 25. The first Gradle run needs access to the repositories configured by the project and Fabric Loom. The wrapper pins Gradle 9.7.0.

```powershell
$env:SIH_OPENAL_LOOPBACK = '1'
./gradlew.bat --no-daemon clean build compileGametestJava '-Ploader_version=0.20.1' '-PclientGameTests'
./tools/Verify-Build.ps1
```

The loopback test requires OpenAL Soft. Check the Gradle test report for skips. The compile task builds the client GameTest sources. Use an existing profile for manual listening and GUI testing.

The runtime and sources JARs are in `build/libs`. Only the runtime JAR belongs in the game’s `mods` directory. See [third-party notices](../../THIRD_PARTY_NOTICES.md) before distributing a build.
