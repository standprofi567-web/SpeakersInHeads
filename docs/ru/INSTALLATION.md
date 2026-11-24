# Установка

Подготовь Minecraft Java Edition 26.3 и Java 25. Установи [Fabric Loader](https://fabricmc.net/use/installer/) и [Fabric API для 26.3](https://modrinth.com/mod/fabric-api) в один профиль. Сборку проверяли с Loader 0.20.1.

1. Скачай `speakersinheads-0.8.0-beta.jar` из [GitHub Releases](../../../../releases).
2. Закрой Minecraft, открой каталог активного профиля в лаунчере и найди папку `mods`.
3. При обновлении сохрани предыдущий JAR SpeakersInHeads вне `mods`. Остальные файлы профиля оставь на месте.
4. Положи основной JAR в `mods`. Туда же устанавливается Fabric API. JAR с исходниками нужен для разработки.
5. Запусти существующий профиль, зажми **Shift** и нажми **ПКМ** по голове игрока на полу или стене.

Чтобы проверить загрузку в PowerShell, выполни `Get-FileHash .\speakersinheads-0.8.0-beta.jar -Algorithm SHA256` и сравни результат с `SHA256SUMS.txt` из того же релиза. Для отката закрой Minecraft и верни сохранённый JAR. В `mods` должна остаться одна версия SpeakersInHeads.

Версии Sound Physics Remastered указаны в разделе [Совместимость](COMPATIBILITY.md).
