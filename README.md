# Rigger

> Docker Swarm Operator — Kubernetes-like primitives, without the complexity.

Rigger é uma plataforma 100% Java que abstrai o Docker Swarm e expõe
recursos familiares ao Kubernetes (Deployment, Service, ConfigMap, Secret, HPA),
com RBAC, GitOps, autoscaling e UI nativa.
Uma equipa pequena consegue ter um cluster de produção funcional **num dia**.

---

## Conteúdo

1. [Pré-requisitos](#1-pré-requisitos)
2. [Início rápido (dev, 1 nó local)](#2-início-rápido-dev-1-nó-local)
3. [Cluster real (multi-nó via SSH)](#3-cluster-real-multi-nó-via-ssh)
4. [Ligar a um Swarm já existente](#4-ligar-a-um-swarm-já-existente)
5. [Windows (Docker Desktop)](#5-windows-docker-desktop)
6. [Instalar o riggerctl (CLI)](#6-instalar-o-riggerctl-cli)
7. [Referência do CLI](#7-referência-do-cli)
8. [Manifests YAML](#8-manifests-yaml)
9. [Segurança e acesso](#9-segurança-e-acesso)
10. [Web UI](#10-web-ui)
11. [GitOps](#11-gitops)
12. [Variáveis de ambiente](#12-variáveis-de-ambiente)
13. [Build do projecto](#13-build-do-projecto)

---

## 1. Pré-requisitos

| Componente | Versão mínima | Notas |
|---|---|---|
| Java | 21 LTS | Para o servidor |
| Docker Engine | 24+ | No servidor/managers |
| Docker Swarm | activado | `docker swarm init` no manager |
| riggerctl | qualquer | CLI para o developer |

O `riggerctl` **não precisa de Java** — é um binário nativo ou JAR standalone.

---

## 2. Início rápido (dev, 1 nó local)

O modo dev corre tudo na máquina local sem SSH nem Swarm real.

```bash
# 1. Inicializar Swarm localmente (uma só vez)
docker swarm init

# 2. Arrancar o servidor
RIGGER_ATTACH_EXISTING_SWARM=true \
java -jar rigger-server.jar

# No Windows (PowerShell):
$env:RIGGER_ATTACH_EXISTING_SWARM = "true"
java -jar rigger-server.jar
```

O servidor inicia em https://localhost:7433 (certificado auto-assinado em dev).

```bash
# 3. Inicializar o CLI (aceitar o certificado auto-assinado)
riggerctl init --server https://localhost:7433 --insecure

# 4. Verificar ligação
riggerctl whoami

# 5. Aplicar o primeiro manifest
riggerctl apply -f examples/deployment-sample.yaml -n default --insecure
```

Abrir a UI: https://localhost:7433/ui

---

## 3. Cluster real (multi-nó via SSH)

### 3.1 Criar rigger.cluster.yaml

```yaml
apiVersion: rigger.io/v1
kind: Cluster
metadata:
  name: prod-angola
spec:
  docker:
    version: "26.1"
    channel: stable
  defaults:
    ssh:
      user: ubuntu
      privateKeyPath: ~/.ssh/rigger_id_ed25519
      port: 22
  nodes:
    - name: manager-01
      ip: 10.0.0.10
      role: manager
      primary: true      # swarm init corre aqui

    - name: manager-02
      ip: 10.0.0.11
      role: manager

    - name: worker-01
      ip: 10.0.0.20
      role: worker

    - name: worker-02
      ip: 10.0.0.21
      role: worker
      ssh:               # credenciais diferentes por nó (opcional)
        user: admin
        privateKeyPath: ~/.ssh/admin_key
        port: 22
```

### 3.2 Provisionar o cluster

```bash
riggerctl cluster up --file rigger.cluster.yaml
```

O Rigger vai:
1. Verificar SSH para todos os nós em paralelo
2. Instalar Docker nos nós que não o têm (detecta Ubuntu/Debian/RHEL automaticamente)
3. Correr `docker swarm init` no nó `primary: true`
4. Juntar os managers e workers ao Swarm

Saída esperada:
```
[1/4] Checking SSH connectivity...   ✓ 4/4 reachable
[2/4] Provisioning manager-01...     ✓ Docker 26.1 installed, Swarm initialised
[3/4] Provisioning remaining nodes (parallel)...
      manager-02  ✓ joined as manager
      worker-01   ✓ joined as worker
      worker-02   ✓ joined as worker
[4/4] Persisting cluster state...    ✓
Cluster ready. 2 managers · 2 workers
```

### 3.3 Adicionar um nó (sem downtime)

Adicionar o nó ao `rigger.cluster.yaml` e correr:

```bash
riggerctl cluster sync --file rigger.cluster.yaml
```

O Rigger detecta o nó novo e provisiona-o automaticamente.

---

## 4. Ligar a um Swarm já existente

Se o Swarm já está criado (manualmente ou por outra ferramenta):

```bash
# Apontar para o socket do Docker já com Swarm activo
RIGGER_ATTACH_EXISTING_SWARM=true \
DOCKER_SOCKET=/var/run/docker.sock \
java -jar rigger-server.jar
```

O Rigger vai detectar o Swarm existente e começar a reconciliar.
Não corre `docker swarm init` nem toca em nós existentes.

---

## 5. Windows (Docker Desktop)

O Docker Desktop no Windows usa uma named pipe em vez de Unix socket.
O Rigger detecta Windows automaticamente e troca o socket.

```powershell
# Não é necessária configuração extra — o Rigger detecta Windows automaticamente
$env:RIGGER_ATTACH_EXISTING_SWARM = "true"
java -jar rigger-server.jar
```

Se necessário overrider manualmente:

```yaml
# application-local.yaml
rigger:
  docker:
    socket: "npipe:////./pipe/docker_engine"
```

**Nota sobre Swarm no Windows:** o Docker Desktop no Windows tem suporte
a Swarm limitado. Para produção, use Linux. Para dev local no Windows,
o modo de 1 nó funciona correctamente.

---

## 6. Instalar o riggerctl (CLI)

### Linux/macOS (binário nativo)

```bash
# Descarregar o binário
curl -L https://github.com/myorg/rigger/releases/latest/download/riggerctl-linux-amd64 \
  -o riggerctl && chmod +x riggerctl

# Mover para o PATH
sudo mv riggerctl /usr/local/bin/

# Verificar
riggerctl --version
```

### Windows (PowerShell)

```powershell
# Descarregar riggerctl.exe
Invoke-WebRequest -Uri "https://github.com/myorg/rigger/releases/latest/download/riggerctl-windows-amd64.exe" `
  -OutFile "riggerctl.exe"

# Mover para um directório no PATH (ex: C:\tools\)
Move-Item riggerctl.exe C:\tools\riggerctl.exe
```

### Alternativa: JAR (sem GraalVM)

```bash
java -jar riggerctl.jar <comando>
```

### Inicializar o CLI

```bash
# Servidor com certificado válido:
riggerctl init --server https://10.0.0.10:7433

# Servidor com certificado auto-assinado (dev/test):
riggerctl init --server https://10.0.0.10:7433 --insecure

# Guardar o flag --insecure permanentemente (evita repetir em cada comando):
# O flag é guardado em ~/.rigger/config após o init --insecure
```

---

## 7. Referência do CLI

### Cluster

```bash
# Provisionar cluster a partir do YAML
riggerctl cluster up   --file rigger.cluster.yaml

# Sincronizar (adicionar/remover nós)
riggerctl cluster sync --file rigger.cluster.yaml

# Estado actual
riggerctl cluster status

# Ver nós
riggerctl get nodes
```

### Workloads

```bash
# Aplicar manifests
riggerctl apply -f deployment.yaml                    # ficheiro único
riggerctl apply -f ./manifests/                       # directório inteiro
riggerctl apply -f docker-compose.yml -n staging      # Compose v3

# Listar recursos
riggerctl get deployments -n production
riggerctl get services    -n production
riggerctl get pods        -n production
riggerctl get configmaps  -n production
riggerctl get secrets     -n production   # mostra só metadata, nunca valores

# Detalhe de um deployment
riggerctl get deployments payments-api -n production -o json

# Escalar
riggerctl scale deployment payments-api --replicas 5 -n production

# Apagar (pede confirmação)
riggerctl delete deployment payments-api -n production

# Logs em tempo real
riggerctl logs payments-api-pod-xyz -n production --follow
```

### Identidade e acesso

```bash
# Ver identidade actual
riggerctl whoami

# Criar um novo utilizador (cluster-admin only)
riggerctl user create alice --role deployer --namespace production -p <password>

# Revogar acesso
riggerctl user revoke alice
```

### Flags globais

| Flag | Descrição |
|---|---|
| `-i, --insecure` | Desactiva verificação TLS (certificados auto-assinados) |
| `-n, --namespace` | Namespace alvo (default: valor em `~/.rigger/config`) |
| `-o, --output` | Formato de saída: `table` (default), `json`, `wide` |
| `--help` | Ajuda do comando |

---

## 8. Manifests YAML

### Deployment com HPA

```yaml
apiVersion: rigger.io/v1
kind: Deployment
metadata:
  name: payments-api
  namespace: production
  labels:
    app: payments
    team: fintech
spec:
  replicas: 3
  image: myregistry/payments:1.4.2
  env:
    - name: DB_URL
      valueFrom:
        configMapKeyRef:
          name: payments-config
          key: db.url
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: payments-secrets
          key: db.password
  resources:
    limits:
      cpu: "0.5"
      memory: "512Mi"
  strategy:
    type: RollingUpdate
    maxUnavailable: 1
    delaySeconds: 10
  hpa:
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70
    scaleDownCooldownSeconds: 180
```

### Service

```yaml
apiVersion: rigger.io/v1
kind: Service
metadata:
  name: payments-svc
  namespace: production
spec:
  selector:
    app: payments
  ports:
    - port: 80
      targetPort: 8080
  type: LoadBalancer    # ClusterIP | LoadBalancer
```

### ConfigMap

```yaml
apiVersion: rigger.io/v1
kind: ConfigMap
metadata:
  name: payments-config
  namespace: production
spec:
  data:
    db.url:  "jdbc:postgresql://db:5432/payments"
    app.env: "production"
```

### Secret

```yaml
apiVersion: rigger.io/v1
kind: Secret
metadata:
  name: payments-secrets
  namespace: production
spec:
  data:
    db.password: cGFzc3dvcmQxMjM=   # base64 do valor real
    api.key:     c2VjcmV0LWtleQ==
```

```bash
# Codificar um valor em base64:
echo -n "minhapassword" | base64
```

### Suporte a Docker Compose v3

O Rigger aceita directamente `docker-compose.yml`. A deteção é feita pelo conteúdo (um mapa
`services` no topo, sem `apiVersion`/`kind`), no servidor, pelo que funciona tanto a partir do
`riggerctl` como da consola web:

```bash
riggerctl apply -f docker-compose.yml -n staging
# Converte: services → Deployment, configs → ConfigMap, secrets → Secret
```

---

## 9. Segurança e acesso

### Roles disponíveis

| Role | Permissões | Âmbito |
|---|---|---|
| `cluster-admin` | Tudo, incluindo gestão de utilizadores | Global |
| `deployer` | apply, scale, delete, get, logs | Por namespace |
| `viewer` | get e logs apenas | Por namespace |
| `gitops-agent` | apply apenas (para CI/CD) | Por namespace |

### Adicionar um utilizador

```bash
# 1. O admin cria a conta e atribui uma role (autenticação é por username/password + JWT)
riggerctl user create alice --role deployer --namespace production -p <password>

# 2. Alice inicializa o CLI e faz login
riggerctl init --server https://10.0.0.10:7433
riggerctl login -u alice

# 3. Alice pode agora usar o CLI
riggerctl get deployments -n production
```

### Princípios de segurança

- **Autenticação por JWT** — login com username/password emite um token JWT de curta duração; não há sessões nem estado no servidor entre pedidos
- **Namespaces obrigatórios** — não há recursos sem namespace
- **Secrets cifrados** — AES-256-GCM em repouso, nunca aparecem em logs nem no audit log
- **Audit log imutável** — todas as operações são registadas (quem, o quê, quando, de onde)
- **Deny by default** — qualquer acesso não autorizado retorna 403

---

## 10. Web UI

Após o servidor arrancar, a UI está disponível em:

```
https://<host>:7433/ui
```

Em dev local: https://localhost:7433/ui

A UI oferece:
- **Dashboard** — estado geral do cluster e nós
- **Nodes** — lista de nós com status e role
- **Deployments** — lista com réplicas actuais/desejadas, botões de scale e delete
- **Services** — endpoints e portas
- **Secrets** — lista (sem valores, por design de segurança)
- **Audit Log** — histórico de operações com filtros
- **GitOps** — estado do agente de sincronização Git

---

## 11. GitOps

Configurar o agente GitOps no `application-local.yaml` ou via variáveis de ambiente:

```yaml
rigger:
  gitops:
    enabled:              true
    repository:           git@github.com:myorg/infra.git
    branch:               main
    sshKeyPath:           /etc/rigger/gitops-key
    poll-interval-seconds: 60
    manifestPaths:
      - manifests/production/
    namespaceMapping:
      manifests/production/: production
```

O agente faz poll a cada 60 segundos (configurável).
Quando detecta um novo commit, aplica todos os manifests modificados.
O agente tem role `gitops-agent` — só pode fazer `apply`, não pode `delete` nem gerir utilizadores.

---

## 12. Variáveis de ambiente

| Variável | Obrigatória | Default | Descrição |
|---|---|---|---|
| `RIGGER_MASTER_KEY` | Produção | (gerada em dev) | Chave AES-256 para cifrar secrets. `openssl rand -base64 32` |
| `RIGGER_JWT_KEY` | Produção | (insegura) | Chave de assinatura JWT. `openssl rand -base64 32` |
| `TLS_KEYSTORE_PATH` | Produção | classpath dev cert | Caminho para o keystore PKCS12 |
| `TLS_KEYSTORE_PASSWORD` | Produção | `rigger-dev` | Password do keystore |
| `DOCKER_SOCKET` | Não | `/var/run/docker.sock` | Socket do Docker. Em Windows: `npipe:////./pipe/docker_engine` |
| `DOCKER_HOST` | Não | — | Docker remoto TCP, ex: `tcp://10.0.0.10:2375` |
| `RIGGER_ATTACH_EXISTING_SWARM` | Não | `false` | `true` para ligar a um Swarm já existente (sem provisionar) |
| `RIGGER_DB_PATH` | Não | `./rigger-state.db` | Caminho para o ficheiro SQLite |
| `RIGGER_ADMIN_NAME` | Não | `admin` | Nome do utilizador administrador de bootstrap |
| `RIGGER_GITOPS_ENABLED` | Não | `false` | Activar agente GitOps |
| `RIGGER_GITOPS_REPO` | Não | — | URL do repositório Git |

### Gerar chaves para produção

```bash
# Chave mestra para secrets
export RIGGER_MASTER_KEY=$(openssl rand -base64 32)

# Chave JWT
export RIGGER_JWT_KEY=$(openssl rand -base64 32)

# Gerar keystore TLS auto-assinado (para começar)
keytool -genkeypair \
  -alias rigger \
  -keyalg RSA -keysize 2048 \
  -storetype PKCS12 \
  -keystore server.p12 \
  -validity 3650 \
  -storepass mypassword \
  -dname "CN=rigger.mycompany.ao"

export TLS_KEYSTORE_PATH=./server.p12
export TLS_KEYSTORE_PASSWORD=mypassword
```

---

## 13. Build do projecto

### Pré-requisitos de build

- Java 21 LTS
- Maven 3.9+
- Node.js 20+ (apenas para o frontend)

### Build completo

```bash
cd rigger/

# Um único comando: o Maven constrói a consola Angular e embute-a no jar.
mvn clean package -DskipTests

# A iterar apenas no backend? Salta o npm por completo:
mvn clean package -DskipTests -Dui.skip=true

# Artefactos gerados:
# rigger-server/target/rigger-server.jar        ← servidor completo
# rigger-cli/target/riggerctl.jar               ← CLI (JAR)
```

### Build apenas do servidor (sem frontend)

```bash
mvn clean package -DskipTests -pl rigger-server -am
```

### Build com testes

```bash
mvn clean verify
```

### Executar em dev

```bash
# Terminal 1: frontend com hot reload
cd rigger-console && npm start

# Terminal 2: servidor Java
RIGGER_ATTACH_EXISTING_SWARM=true \
mvn spring-boot:run -pl rigger-server
```

---

## Troubleshooting

### "TLS handshake failed" no CLI

```bash
# Usar --insecure em todos os comandos (certificado auto-assinado):
riggerctl get nodes --insecure

# Ou guardar permanentemente no config:
riggerctl init --server https://host:7433 --insecure
```

### "Docker API error: invalid filter"

Actualizar para a versão mais recente — este bug foi corrigido na migração para `docker-java`.

### "Cannot connect to Docker daemon" no Windows

```powershell
# Verificar se o Docker Desktop está a correr:
docker info

# Verificar a named pipe:
Test-Path "//./pipe/docker_engine"

# Forçar o socket correcto:
$env:DOCKER_SOCKET = "npipe:////./pipe/docker_engine"
```

### Servidor não arranca sem RIGGER_MASTER_KEY

Em dev, o Rigger gera uma chave aleatória automaticamente e avisa nos logs.
Em produção, definir explicitamente:

```bash
export RIGGER_MASTER_KEY=$(openssl rand -base64 32)
```

### "Access Denied" (403) no CLI

```bash
# Verificar qual a role atribuída:
riggerctl whoami

# O admin pode revogar e recriar o utilizador com outra role
# (não há ainda um comando para actualizar a role de um utilizador existente):
riggerctl user revoke alice
riggerctl user create alice --role deployer --namespace production -p <password>
```

---

*Rigger é um projecto open-source. Contribuições são bem-vindas.*
