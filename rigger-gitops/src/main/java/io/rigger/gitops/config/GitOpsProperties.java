package io.rigger.gitops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the GitOps agent.
 *
 * <pre>
 * rigger:
 *   gitops:
 *     enabled: true
 *     repository: git@github.com:myorg/infra.git
 *     branch: main
 *     sshKeyPath: /etc/rigger/gitops-key
 *     authType: ssh              # or https
 *     httpsUsername: gitops-bot
 *     httpsToken: ${RIGGER_GITOPS_HTTPS_TOKEN}
 *     pollIntervalSeconds: 60
 *     manifestPaths:
 *       - manifests/production/
 *     namespaceMapping:
 *       manifests/production/: production
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rigger.gitops")
public class GitOpsProperties {
    private boolean     enabled              = false;
    private String      repository;
    private String      branch               = "main";
    private String      sshKeyPath;
    private String      authType             = "ssh";
    private String      httpsUsername;
    private String      httpsToken;
    private int         pollIntervalSeconds  = 60;
    private List<String> manifestPaths       = List.of("manifests/");
    private Map<String,String> namespaceMapping = Map.of();

    public boolean isEnabled()                  { return enabled; }
    public void setEnabled(boolean e)           { this.enabled = e; }
    public String getRepository()               { return repository; }
    public void setRepository(String r)         { this.repository = r; }
    public String getBranch()                   { return branch; }
    public void setBranch(String b)             { this.branch = b; }
    public String getSshKeyPath()               { return sshKeyPath; }
    public void setSshKeyPath(String k)         { this.sshKeyPath = k; }
    public String getAuthType()                  { return authType; }
    public void setAuthType(String a)           { this.authType = a; }
    public String getHttpsUsername()             { return httpsUsername; }
    public void setHttpsUsername(String u)      { this.httpsUsername = u; }
    public String getHttpsToken()                { return httpsToken; }
    public void setHttpsToken(String t)         { this.httpsToken = t; }
    public int getPollIntervalSeconds()         { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int s)   { this.pollIntervalSeconds = s; }
    public List<String> getManifestPaths()      { return manifestPaths; }
    public void setManifestPaths(List<String> p){ this.manifestPaths = p; }
    public Map<String,String> getNamespaceMapping() { return namespaceMapping; }
    public void setNamespaceMapping(Map<String,String> m) { this.namespaceMapping = m; }
}
