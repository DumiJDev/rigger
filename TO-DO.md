# TO-DO — lista de desejos

Isto é a minha lista de desejos pessoal para o Rigger, não um plano nem um compromisso de
entrega. São ideias que quero explorar mais cedo ou mais tarde; cada uma precisa da sua própria
decisão de arquitetura antes de virar trabalho a sério.

## Volumes com partilha entre nós

Quero suporte a volumes no `DeploymentSpec`, mas a sério — não bind mounts presos ao nó onde a
task calha correr, e sim algo que funcione num Swarm multi-nó com os dados a seguir o container
para onde quer que ele seja reagendado. Hoje `DeploymentSpec` não tem campo de volumes de
propósito: o Traefik de ingress é a única coisa no cluster com bind mount ao
`/var/run/docker.sock`, e isso é deliberado — um campo de volumes livre deixaria qualquer
DEPLOYER com acesso a um namespace montar o socket do Docker e ganhar o cluster todo. Para fazer
isto bem preciso de escolher uma storage layer real (NFS, GlusterFS, um driver de volume Docker
plugável) e desenhar um allowlist/RBAC próprio para volumes — não é para abrir uma porta de trás
como a que já existe só para o Traefik.

## Segurança de rede do cluster

Quero isolamento de rede real entre workloads dentro do cluster, não só na borda. Hoje a única
coisa parecida com "segurança de rede" é o Traefik (host/path/TLS de entrada) e o RBAC por
namespace — nada controla quem fala com quem dentro das overlay networks do Swarm. Gostava de
algo ao estilo `NetworkPolicy` do Kubernetes, adaptado ao modelo de overlay networks do Swarm:
regras por namespace ou por label selector que decidam que Deployments podem trocar tráfego entre
si e quais ficam isolados, cobrindo o tráfego leste-oeste que hoje não tem controlo nenhum.

## Mais funcionalidades para uma equipa entregar grandes soluções desde o day zero

Coisas que já estão documentadas como gap ou como "fora de âmbito" e que valeria a pena
reconsiderar com uma equipa maior em mente:

- **HA/multi-instância real do `rigger-server`** — hoje é explicitamente fora de âmbito porque a
  reconciliação de órfãos é cluster-wide e relativa à própria base de dados: dois servidores no
  mesmo Swarm apagam-se mutuamente. Resolver isto (filtro de órfãos por namespace/instância, ou
  coordenação entre instâncias) é o que abriria caminho para alta disponibilidade a sério.
- **UI de administração de RBAC** — hoje só existe o mecanismo de enforcement
  (`RbacPolicyEngine.authorize`), sem nenhuma interface para uma equipa gerir papéis e permissões
  sem mexer em código/config.
- **mTLS real** — o modelo de auth atual é só JWT + password; o certificado de cliente é aceite
  na ligação TLS mas nunca é validado ou usado (`RiggerIdentity.certSerial` existe na base de
  dados e não é lido por ninguém). Para um ambiente enterprise a sério, mTLS ponta-a-ponta faria
  sentido.
- **`riggerctl logs --follow`** — está quebrado de ponta a ponta hoje: não há endpoint no
  servidor para isto e o comando do CLI nem sequer passa pelo cliente HTTP autenticado que o
  resto do CLI usa.
- **Editar o YAML de um recurso em vez de só create/replace** — a consola e o `apply` de hoje só
  sabem criar ou substituir por inteiro; não há edição incremental de um recurso já aplicado.
- **`env[].valueFrom` e `secretRefs` que hoje são validados e depois silenciosamente descartados**
  — uma variável de ambiente com `configMapKeyRef`/`secretKeyRef` passa a validação, "aplica com
  sucesso" e nunca chega ao container; o mesmo para `secretRefs` num Deployment. Isto devia ou
  funcionar a sério ou deixar de ser aceite na validação — hoje engana quem escreve o manifesto.
- **`cluster up`/`cluster sync`** — a lógica já existe em `ClusterOrchestrator`, só não está
  ligada a nenhum controller/endpoint, apesar de já ser referida pelo `riggerctl`/README.
