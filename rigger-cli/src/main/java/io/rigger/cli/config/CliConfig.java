package io.rigger.cli.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.rigger.cli.client.RiggerApiClient;
import java.io.IOException;
import java.nio.file.*;

/**
 * CLI configuration stored at ~/.rigger/config.
 * Token stored separately at ~/.rigger/token (written by riggerctl login).
 */
public record CliConfig(
        @JsonProperty("server")           String server,
        @JsonProperty("certPath")         String certPath,
        @JsonProperty("keyPath")          String keyPath,
        @JsonProperty("caCertPath")       String caCertPath,
        @JsonProperty("defaultNamespace") String defaultNamespace,
        @JsonProperty("insecure")         boolean insecure
) {
    private static final Path CONFIG_PATH =
        Path.of(System.getProperty("user.home"), ".rigger", "config");
    private static final Path TOKEN_PATH =
        Path.of(System.getProperty("user.home"), ".rigger", "token");

    public static CliConfig load() throws IOException {
        if (!Files.exists(CONFIG_PATH))
            throw new IOException(
                "CLI not initialised. Run:\n" +
                "  riggerctl init --server https://<host>:7433 [--insecure]");
        return new ObjectMapper(new YAMLFactory()).readValue(CONFIG_PATH.toFile(), CliConfig.class);
    }

    public void save() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        new ObjectMapper(new YAMLFactory()).writeValue(CONFIG_PATH.toFile(), this);
    }

    /**
     * Creates an API client.
     * Automatically injects the saved token from ~/.rigger/token.
     * If no token exists, requests will return 401 — run `riggerctl login` first.
     */
    public RiggerApiClient client(boolean insecureOverride) {
        String token = loadToken();
        return new RiggerApiClient(server, insecure || insecureOverride, caCertPath, token);
    }

    public RiggerApiClient client() { return client(false); }

    /**
     * Expands a path taken from the CLI config or a flag. Single implementation for the whole
     * CLI (see {@link RiggerApiClient}): besides {@code ~/} it accepts the spellings a Windows
     * user will type — {@code ~\} and {@code %USERPROFILE%} — which Java never expands itself.
     */
    public static Path expandPath(String path) {
        String p = path.trim();
        if (p.regionMatches(true, 0, "%USERPROFILE%", 0, 13)) return underHome(p.substring(13));
        if (p.equals("~"))                                    return underHome("");
        if (p.startsWith("~/") || p.startsWith("~\\"))         return underHome(p.substring(1));
        return Path.of(p);
    }

    /** Resolves a home-relative remainder, accepting either separator so Windows input works. */
    private static Path underHome(String rest) {
        Path base = Path.of(System.getProperty("user.home"));
        for (String segment : rest.split("[/\\\\]")) {
            if (!segment.isEmpty()) base = base.resolve(segment);
        }
        return base;
    }

    private String loadToken() {
        try {
            if (Files.exists(TOKEN_PATH)) return Files.readString(TOKEN_PATH).trim();
        } catch (IOException ignored) {}
        return null;
    }
}
