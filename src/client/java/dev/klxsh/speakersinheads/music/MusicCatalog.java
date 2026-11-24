package dev.klxsh.speakersinheads.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public final class MusicCatalog {
    public record Entry(String id, String title, String artist, int seconds,
                        String detail, String audio, String artwork) {
        public boolean canImport() { return audio != null && !audio.isBlank(); }
    }

    private MusicCatalog() {}

    public static boolean jamendoConfigured() { return !jamendoId().isBlank(); }

    private static String jamendoId() {
        String id = System.getenv("SIH_JAMENDO_CLIENT_ID");
        return id == null ? "" : id.strip();
    }

    static List<Entry> licensedSearch(String query) throws IOException {
        return jamendoRequest("&search=" + encode(query));
    }
    static Entry lookupJamendo(String id) throws IOException {
        if (!id.matches("[0-9]{1,18}")) return null;
        return jamendoRequest("&id=" + id).stream().filter(track -> track.id().equals(id)).findFirst().orElse(null);
    }

    private static List<Entry> jamendoRequest(String selection) throws IOException {
        String id = jamendoId();
        if (id.isEmpty()) throw new RequestException("Set SIH_JAMENDO_CLIENT_ID for the launcher and restart Minecraft.");
        URI uri = URI.create("https://api.jamendo.com/v3.0/tracks/?format=json&limit=25"
                + "&audiodlformat=mp32&client_id=" + encode(id) + selection);
        var root = json(uri, CatalogHttp.JAMENDO).getAsJsonObject();
        if (!root.has("results")) throw new RequestException("Jamendo rejected the catalog request.");
        List<Entry> result = new ArrayList<>();
        for (var element : root.getAsJsonArray("results")) {
            var row = element.getAsJsonObject();
            String audio = row.has("audiodownload_allowed") && row.get("audiodownload_allowed").isJsonPrimitive()
                    && row.get("audiodownload_allowed").getAsBoolean() ? string(row, "audiodownload") : "";
            String detail = audio.isBlank() ? "Catalog only" : "Download permitted";
            if (!string(row, "license_ccurl").isBlank()) detail += " / Creative Commons";
            result.add(new Entry( string(row, "id"), string(row, "name"), string(row, "artist_name"),
                    integer(row, "duration"), detail, audio, string(row, "image")));
            if (result.size() == 100) break;
        }
        return List.copyOf(result);
    }

    static JsonElement json(URI uri, Set<String> zones) throws IOException {
        try {
            return JsonParser.parseString(new String(CatalogHttp.bytes(uri, zones, 2 * 1024 * 1024),
                    StandardCharsets.UTF_8));
        } catch (JsonParseException failure) {
            throw new RequestException("Provider sent an unreadable response.");
        }
    }

    public static AuthorizedImport.Result download(Entry entry, Path library, IntConsumer progress) throws IOException {
        Entry current = lookupJamendo(entry.id());
        if (current == null || !current.canImport()) throw new RequestException("Download not permitted.");
        return AuthorizedImport.fromJamendo(current, library, progress);
    }

    public static final class RequestException extends IOException {
        RequestException(String message) { super(message); }
    }

    public static String safeMessage(Exception failure) {
        return failure instanceof RequestException ? failure.getMessage() : "Request failed. Check the service and connection.";
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String string(JsonObject row, String field) {
        if (row == null || !row.has(field) || !row.get(field).isJsonPrimitive()) return "";
        return row.get(field).getAsString();
    }

    static int integer(JsonObject row, String field) {
        try { return Integer.parseInt(string(row, field)); }
        catch (NumberFormatException invalid) { return 0; }
    }
}
