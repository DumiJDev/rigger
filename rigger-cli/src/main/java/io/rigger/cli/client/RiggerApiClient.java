package io.rigger.cli.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import javax.net.ssl.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for riggerctl → rigger-server.
 *
 * TLS modes (in priority order):
 *   1. insecure=true  → trust all certs (dev/self-signed)
 *   2. RIGGER_INSECURE=true env var → same as insecure=true
 *   3. caCertPath set → load custom CA cert
 *   4. default → system trust store
 */
public class RiggerApiClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final String       baseUrl;
    private final ObjectMapper mapper;
    private final String       token;

    public RiggerApiClient(String baseUrl, boolean insecure, String caCertPath, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.mapper  = new ObjectMapper().registerModule(new JavaTimeModule());
        this.token   = token;

        // Also honour env var RIGGER_INSECURE=true
        boolean actualInsecure = insecure
            || "true".equalsIgnoreCase(System.getenv("RIGGER_INSECURE"));

        this.http = buildClient(actualInsecure, caCertPath);
    }

    // ── HTTP verbs ─────────────────────────────────────────────────────────

    public <T> T get(String path, Class<T> type) throws IOException {
        try (var resp = http.newCall(req(path).get().build()).execute()) {
            assertOk(resp, "GET", path);
            return mapper.readValue(resp.body().string(), type);
        }
    }

    public Map<?,?> post(String path, Object body) throws IOException {
        try (var resp = http.newCall(req(path).post(toBody(body)).build()).execute()) {
            assertOk(resp, "POST", path);
            return mapper.readValue(resp.body().string(), Map.class);
        }
    }

    public void delete(String path) throws IOException {
        try (var resp = http.newCall(req(path).delete().build()).execute()) {
            assertOk(resp, "DELETE", path);
        }
    }

    // ── private ────────────────────────────────────────────────────────────

    private Request.Builder req(String path) {
        var b = new Request.Builder().url(baseUrl + path);
        if (token != null && !token.isBlank())
            b.header("Authorization", "Bearer " + token);
        return b;
    }

    private RequestBody toBody(Object o) {
        try { return RequestBody.create(mapper.writeValueAsBytes(o), JSON); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private void assertOk(Response r, String method, String path) throws IOException {
        if (r.isSuccessful()) return;
        String body = r.body() != null ? r.body().string() : "";
        switch (r.code()) {
            case 401 -> throw new ApiException(401,
                "Not authenticated. Run: riggerctl login -u admin [-i]");
            case 403 -> throw new ApiException(403,
                "Access denied. Your role does not have permission for this operation.");
            case 404 -> throw new ApiException(404,
                "Not found: " + path);
            default  -> throw new ApiException(r.code(),
                method + " " + path + " returned " + r.code() + ": " + body);
        }
    }

    private OkHttpClient buildClient(boolean insecure, String caCertPath) {
        var builder = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS);

        if (insecure) {
            applyInsecureTls(builder);
        } else if (caCertPath != null && !caCertPath.isBlank()) {
            tryLoadCaCert(builder, caCertPath);
        }

        return builder.build();
    }

    private void applyInsecureTls(OkHttpClient.Builder builder) {
        try {
            var tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            var sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ tm }, new SecureRandom());
            builder.sslSocketFactory(sc.getSocketFactory(), tm)
                   .hostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure insecure TLS", e);
        }
    }

    private void tryLoadCaCert(OkHttpClient.Builder builder, String caCertPath) {
        var caFile = Path.of(caCertPath.startsWith("~/")
            ? System.getProperty("user.home") + caCertPath.substring(1) : caCertPath);
        if (!Files.exists(caFile)) return;
        try (var is = Files.newInputStream(caFile)) {
            var cf  = java.security.cert.CertificateFactory.getInstance("X.509");
            var ca  = cf.generateCertificate(is);
            var ks  = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("rigger-ca", ca);
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            var sc  = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), null);
            builder.sslSocketFactory(sc.getSocketFactory(),
                (X509TrustManager) tmf.getTrustManagers()[0]);
        } catch (Exception e) {
            System.err.println("[WARN] Could not load CA cert: " + e.getMessage());
        }
    }

    // ── public exception ──────────────────────────────────────────────────

    public static class ApiException extends IOException {
        private final int status;
        public ApiException(int status, String msg) { super(msg); this.status = status; }
        public int     status()         { return status; }
        public boolean isUnauthorized() { return status == 401; }
        public boolean isForbidden()    { return status == 403; }
        public boolean isNotFound()     { return status == 404; }
    }
}
