# Разработка

Установи JDK 25. При первом запуске Gradle нужен доступ к репозиториям проекта и Fabric Loom. Wrapper использует Gradle 9.7.0.

```powershell
$env:SIH_OPENAL_LOOPBACK = '1'
./gradlew.bat --no-daemon clean build compileGametestJava '-Ploader_version=0.20.1' '-PclientGameTests'
./tools/Verify-Build.ps1
```

Loopback-тесту нужен OpenAL Soft. Проверь в отчёте Gradle, что тест выполнился без пропуска. Команда компилирует исходники Client GameTest. Звук и интерфейс проверяй вручную в существующем игровом профиле.

Основной JAR и исходники лежат в `build/libs`. В `mods` кладётся основной JAR. Перед распространением сборки проверь [уведомления о зависимостях](../../THIRD_PARTY_NOTICES.md).
