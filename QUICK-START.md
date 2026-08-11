# Rigger — Quick Start

## O problema mais comum: certificado TLS

O servidor Rigger usa HTTPS com um certificado auto-assinado por defeito.
O CLI rejeita certificados auto-assinados a não ser que uses `--insecure` ou `-i`.

**Regra simples: em dev/teste, adiciona sempre `-i` aos comandos.**

---

## Fluxo completo (primeira vez)

### Passo 1 — Arrancar o servidor

```bash
# Linux/Mac
RIGGER_ATTACH_EXISTING_SWARM=true java -jar rigger-server.jar

# Windows (PowerShell)
$env:RIGGER_ATTACH_EXISTING_SWARM = "true"
java -jar rigger-server.jar
```

Servidor disponível em: https://localhost:7433

---

### Passo 2 — Configurar o CLI (uma só vez por máquina)

```bash
# Com certificado auto-assinado (dev/teste) — usa SEMPRE --insecure aqui
java -jar riggerctl.jar init --server https://localhost:7433 --insecure

# Com IP do servidor remoto
java -jar riggerctl.jar init --server https://10.0.0.10:7433 --insecure
```

O `--insecure` fica gravado em `~/.rigger/config`.
Não precisas de o repetir nos comandos seguintes.

---

### Passo 3 — Login

```bash
# Credenciais default: admin / admin
java -jar riggerctl.jar login

# Ou com password explícita
java -jar riggerctl.jar login -u admin -p admin

# Se o init foi feito SEM --insecure (erro de certificado), adiciona -i
java -jar riggerctl.jar login -i
```

Saída esperada:
```
Password:
✓ Logged in as: admin
  Role:          CLUSTER_ADMIN
  Namespace:     (all)
  Token saved to ~/.rigger/token
```

---

### Passo 4 — Usar o CLI

```bash
# Ver nós do cluster
java -jar riggerctl.jar get nodes

# Criar um utilizador
java -jar riggerctl.jar user create alice --role deployer --namespace production -p senha123

# Listar utilizadores
java -jar riggerctl.jar user list

# Ver estado da ligação
java -jar riggerctl.jar whoami
```

---

## Resolver erros comuns

### "PKIX path building failed" / "certificate_unknown"

```bash
# Adiciona -i ao comando
java -jar riggerctl.jar login -i
java -jar riggerctl.jar get nodes -i

# Ou reconfigura o CLI para sempre ignorar TLS
java -jar riggerctl.jar init --server https://localhost:7433 --insecure
java -jar riggerctl.jar login
# (depois do init --insecure, não precisas de -i nos outros comandos)
```

Ou via variável de ambiente (aplica a tudo):
```bash
export RIGGER_INSECURE=true
java -jar riggerctl.jar login
java -jar riggerctl.jar get nodes
```

### "Not authenticated. Run: riggerctl login"

O token expirou (15 minutos por defeito) ou ainda não fizeste login.
```bash
java -jar riggerctl.jar login -u admin
```

### "Connection refused"

O servidor não está a correr, ou o URL está errado.
```bash
# Verificar URL guardado
java -jar riggerctl.jar whoami

# Reinicializar com URL correcto
java -jar riggerctl.jar init --server https://ENDERECO-CORRECTO:7433 --insecure
```

---

## Referência rápida de comandos

```bash
# Configuração
riggerctl init  --server https://host:7433 --insecure
riggerctl login [-u user] [-p pass] [-i]
riggerctl whoami

# Utilizadores (requer cluster-admin)
riggerctl user create alice --role deployer --namespace production -p pass123
riggerctl user list
riggerctl user revoke alice

# Cluster
riggerctl cluster up     -f rigger.cluster.yaml
riggerctl cluster sync   -f rigger.cluster.yaml
riggerctl cluster status
riggerctl get nodes

# Workloads
riggerctl apply  -f deployment.yaml -n production
riggerctl get    deployments        -n production
riggerctl scale  deployment app     -n production --replicas 3
riggerctl delete deployment app     -n production
riggerctl logs   pod-name           -n production --follow
```

---

## Variáveis de ambiente úteis (servidor)

```bash
RIGGER_ATTACH_EXISTING_SWARM=true   # não provisionar, usar Swarm existente
RIGGER_ADMIN_PASSWORD=minhapass     # password do admin (default: admin)
RIGGER_MASTER_KEY=$(openssl rand -base64 32)  # chave de cifração (produção)
DOCKER_SOCKET=npipe:////./pipe/docker_engine  # Windows: já é o default, define só para outro pipe
DOCKER_HOST=tcp://10.0.0.10:2375              # daemon remoto; tem prioridade sobre DOCKER_SOCKET
```

`DOCKER_SOCKET` e `DOCKER_HOST` são respeitados em **todas** as plataformas, incluindo Windows.
Antes eram silenciosamente ignorados em Windows, porque o perfil `windows` fixava o valor.

Em Windows, o Docker Desktop tem de estar a correr **antes** de aplicares qualquer coisa: o
servidor arranca de qualquer maneira, mas todas as operações de workload falham e o log mostra
`Docker is NOT reachable at ...`. Se o pipe herdado não existir, `docker context inspect` diz
qual é o certo — habitualmente `npipe:////./pipe/dockerDesktopLinuxEngine`.

Nota sobre provisionamento: `riggerctl cluster up` e `cluster sync` só provisionam nós **Linux**
(Debian/RHEL ou `get.docker.com` por SSH). Uma máquina Windows pode alojar o servidor Rigger,
mas não pode ser um nó provisionado. Caminhos de chave SSH aceitam `~/`, `~\` e `%USERPROFILE%`.
