package io.rigger.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security configuration.
 *
 * <pre>
 * rigger:
 *   security:
 *     masterKey:             ${RIGGER_MASTER_KEY}
 *     jwtSigningKey:         ${RIGGER_JWT_KEY}
 *     jwtExpiryMinutes:      15
 *     bootstrapAdminName:    admin
 *     bootstrapAdminPassword: ${RIGGER_ADMIN_PASSWORD:admin}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rigger.security")
public class SecurityProperties {

    private String masterKey          = "";
    private String jwtSigningKey      = "changeme-replace-in-production-min-32-chars";
    private int    jwtExpiryMinutes   = 15;
    private String bootstrapAdminName = "admin";
    /**
     * Blank by default so {@code UserStore} can tell "not configured" apart from an
     * intentional value. In the {@code prod} profile, blank fails startup; elsewhere a
     * random password is generated and logged once. Override with RIGGER_ADMIN_PASSWORD.
     */
    private String bootstrapAdminPassword = "";
    private String caCertPath         = "";

    public String getMasterKey()                    { return masterKey; }
    public void   setMasterKey(String v)            { this.masterKey = v; }
    public String getJwtSigningKey()                { return jwtSigningKey; }
    public void   setJwtSigningKey(String v)        { this.jwtSigningKey = v; }
    public int    getJwtExpiryMinutes()             { return jwtExpiryMinutes; }
    public void   setJwtExpiryMinutes(int v)        { this.jwtExpiryMinutes = v; }
    public String getBootstrapAdminName()           { return bootstrapAdminName; }
    public void   setBootstrapAdminName(String v)   { this.bootstrapAdminName = v; }
    public String getBootstrapAdminPassword()       { return bootstrapAdminPassword; }
    public void   setBootstrapAdminPassword(String v){ this.bootstrapAdminPassword = v; }
    public String getCaCertPath()                   { return caCertPath; }
    public void   setCaCertPath(String v)           { this.caCertPath = v; }
}
