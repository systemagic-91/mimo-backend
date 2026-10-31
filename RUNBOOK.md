# Runbook do backend

Manual de operação: como rodar, configurar, publicar, acompanhar e fazer backup do backend da Luma.

- Tracing (seguir uma requisição pelos logs): [TRACING.md](TRACING.md)
- Métricas (números de saúde do sistema): [METRICAS.md](METRICAS.md)

## 1. Rodar localmente

Requisitos: Java 21 e Docker.

```bash
cp .env.example .env              # defina DB_PASSWORD
docker compose up -d              # PostgreSQL local
./gradlew bootRun                 # Windows: gradlew.bat bootRun
```

Na primeira vez, crie o owner (seção 3). Os testes não precisam de Docker:

```bash
./gradlew clean check
```

## 2. Variáveis de ambiente

| Variável | Obrigatória em produção | Padrão | Para que serve |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Sim (`prod`) | `local` | Liga a configuração de produção (logs em JSON, sem valores padrão inseguros) |
| `PORT` | Não | `8080` | Porta HTTP. O Railway define sozinho |
| `DB_URL` | Sim | local: `jdbc:postgresql://localhost:5432/luma` | URL JDBC do PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | Sim | local: `luma` / `luma123` | Credenciais do banco |
| `JWT_SECRET` | Sim | gerada a cada start em local | Chave dos tokens: Base64 com 32 bytes ou mais. Gere com `openssl rand -base64 32` |
| `CORS_ALLOWED_ORIGINS` | Sim | `http://localhost:5173` | Origens do front, separadas por vírgula |
| `JWT_ISSUER` | Não | `luma-api` | Emissor do token |
| `JWT_ACCESS_TOKEN_TTL` / `JWT_REFRESH_TOKEN_TTL` | Não | `PT15M` / `P30D` | Validade dos tokens |
| `BOOTSTRAP_OWNER_*` | Só na criação do primeiro owner | desligado | Ver seção 3 |
| `LOGIN_MAX_ATTEMPTS` / `LOGIN_RATE_LIMIT_WINDOW` | Não | `5` / `PT15M` | Limite de tentativas de login, contado por IP e por e-mail |
| `REFRESH_COOKIE_NAME` | Não | `luma_refresh` | Nome do cookie da sessão |
| `REFRESH_COOKIE_SECURE` / `REFRESH_COOKIE_SAME_SITE` | Não | `true` / `Strict` | Atributos do cookie. Não mude em produção |
| `UPLOAD_MAX_PER_WINDOW` / `UPLOAD_RATE_LIMIT_WINDOW` | Não | `30` / `PT1M` | Limite de uploads de foto por usuário |
| `IDEMPOTENCY_TTL` | Não | `P1D` | Por quanto tempo uma `Idempotency-Key` repete a resposta |
| `IDEMPOTENCY_RETENTION` | Não | `P7D` | Quanto tempo depois de vencer a chave é apagada |
| `IDEMPOTENCY_CLEANUP_CRON` / `_ZONE` | Não | `0 30 3 * * *` / `America/Sao_Paulo` | Horário da limpeza das chaves |
| `PHOTO_CLEANUP_CRON` / `_ZONE` | Não | `0 0 4 * * *` / `America/Sao_Paulo` | Horário da limpeza de fotos órfãs |
| `TRACING_SAMPLING_PROBABILITY` | Não | `1.0` | Fração das requisições rastreadas (ver TRACING.md) |

Nunca versione valores reais de `JWT_SECRET`, `DB_PASSWORD` ou senhas.

## 3. Primeiro owner (bootstrap)

O sistema não cria nenhum usuário padrão. Para criar o primeiro owner:

1. Defina as variáveis e suba a aplicação **uma vez**:
   ```text
   BOOTSTRAP_OWNER_ENABLED=true
   BOOTSTRAP_OWNER_NAME=Nome da dona
   BOOTSTRAP_OWNER_EMAIL=dona@exemplo.com
   BOOTSTRAP_OWNER_PASSWORD=<senha de uso único com 12+ caracteres>
   ```
2. Confirme no log que a aplicação subiu e faça login.
3. **Imediatamente** mude `BOOTSTRAP_OWNER_ENABLED=false` e apague `BOOTSTRAP_OWNER_PASSWORD`. O bootstrap nunca sobrescreve uma conta existente, mas a senha não deve ficar guardada no ambiente.

Os demais usuários são criados por um owner em `POST /api/v1/users`, com uma senha temporária entregue pessoalmente; a pessoa é obrigada a trocá-la no primeiro login. Se alguém esquecer a senha, um owner usa `POST /api/v1/users/{id}/reset-password` (não há recuperação por e-mail). Se o único owner esquecer a própria senha, não há rota para isso: rode o bootstrap de novo com outro e-mail para criar um owner novo e, com ele, redefina a senha da conta antiga.

## 4. Migrations do banco (Flyway)

- Ficam em `src/main/resources/db/migration` (`V1__...sql`, `V2__...sql`, ...).
- São aplicadas **automaticamente** quando a aplicação sobe. Em seguida, o Hibernate confere se as entidades batem com o banco (`ddl-auto: validate`); se não baterem, a aplicação não sobe.
- **Nunca altere uma migration que já rodou em algum banco.** Para mudar o schema, crie a próxima versão (`V6__...sql`).
- O CI aplica todas as migrations num PostgreSQL limpo a cada push (seção 7). Migration quebrada = CI vermelho, antes de chegar à produção.

## 5. Deploy no Railway

> Estes passos foram escritos antes do primeiro deploy e não foram executados contra o Railway real. Confira na primeira vez e ajuste este documento se algo mudar.

1. No Railway, crie um projeto e adicione um **PostgreSQL** (New → Database → PostgreSQL).
2. Adicione um serviço a partir do repositório do GitHub (New → GitHub Repo → `mimo-backend`). O Railway detecta o Gradle e faz o build sozinho. O build gera **um único** `.jar` (o "plain jar" foi desligado no `build.gradle` para não confundir o comando de start).
3. Em **Variables** do serviço do backend, cadastre:
   ```text
   SPRING_PROFILES_ACTIVE=prod
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USERNAME=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   JWT_SECRET=<saída de: openssl rand -base64 32>
   CORS_ALLOWED_ORIGINS=https://<seu-front>.vercel.app
   ```
   Os valores `${{Postgres.X}}` são referências do próprio Railway às variáveis do banco (o nome `Postgres` precisa ser o nome do serviço do banco no projeto).
4. Garanta o **Java 21** no build. Se o build reclamar da versão do Java, defina a variável `NIXPACKS_JDK_VERSION=21` (ou a equivalente do builder em uso).
5. Em **Settings → Deploy**, configure o **Healthcheck Path** como `/actuator/health`. O Railway só troca para a versão nova quando ela responder `UP`.
6. Se o Railway não descobrir o comando de start, use:
   ```bash
   java -jar build/libs/mimo-backend-0.0.1-SNAPSHOT.jar
   ```
7. Faça o primeiro deploy com o bootstrap do owner (seção 3) e depois desligue-o.
8. Em **Settings → Networking**, gere o domínio público. O front chama a API pelo proxy `/api` da Vercel (FRONT_22), e `CORS_ALLOWED_ORIGINS` precisa ter exatamente a origem do front (`https://<seu-front>.vercel.app`, sem barra no fim): o refresh e o logout recusam qualquer outra.
9. Confira o IP real: depois do primeiro login pela Vercel, a coluna `ip_address` do último `LOGIN_SUCCEEDED` em `audit_logs` deve ter o seu IP, e não o de um proxy. Se aparecer sempre o mesmo IP para todo mundo, o rate limit de login vai bloquear todos juntos.

A porta é lida da variável `PORT`, que o Railway define (`server.port: ${PORT:8080}`).

## 6. Acompanhar a aplicação

| O quê | Onde | Quem acessa |
|---|---|---|
| Está no ar? | `GET /actuator/health` → `{"status":"UP"}` | Público |
| Logs | Railway → Deployments → View Logs (JSON em produção) | Quem tem acesso ao Railway |
| Uma requisição específica | Buscar o `X-Trace-Id` ou `X-Request-Id` nos logs ([TRACING.md](TRACING.md)) | Quem tem acesso ao Railway |
| Números de saúde e da loja | `GET /actuator/metrics` e `/actuator/prometheus` ([METRICAS.md](METRICAS.md)) | Só owner |
| Quem fez o quê | Tabela `audit_logs` | Acesso ao banco |

A auditoria registra login (sucesso e falha), troca de senha, vendedoras, configurações, recebimento e etiquetagem de peças, upload de fotos, criação de lote, venda e devolução, sempre com ator, ação, entidade, IP e horário. Nunca guarda senhas, tokens ou o conteúdo das fotos.

## 7. CI (GitHub Actions)

A cada push e pull request, `.github/workflows/ci.yml` roda dois jobs:

1. **Testes e build:** `./gradlew clean check bootJar` com Java 21 e publica o `.jar` como artefato do workflow (fica 14 dias disponível na aba **Actions**).
2. **Migrations em PostgreSQL limpo:** sobe um PostgreSQL 16 como *service container* do GitHub Actions (não é Testcontainers), inicia o `.jar` com o perfil `prod` e espera `/actuator/health` responder `UP`. Se uma migration for inválida ou o schema não bater com as entidades, a aplicação não sobe e o job falha.

## 8. Backup e restauração

O backup é manual, com `pg_dump`. Os comandos abaixo usam o Docker para não precisar instalar o PostgreSQL na máquina. **Use a mesma versão principal do PostgreSQL do Railway** (veja em Postgres → Settings); os exemplos usam a 16.

### 8.1 Fazer o backup

1. No Railway, abra o serviço **Postgres → Variables** e copie a `DATABASE_PUBLIC_URL` (algo como `postgresql://postgres:SENHA@host.proxy.rlwy.net:12345/railway`).
2. Rode, na pasta onde quer salvar o arquivo:

   Linux/macOS/Git Bash:
   ```bash
   export DATABASE_PUBLIC_URL='postgresql://...'
   docker run --rm -v "$PWD:/backup" postgres:16-alpine \
     pg_dump --format=custom --no-owner --no-privileges \
     --file=/backup/luma-$(date +%Y%m%d).dump "$DATABASE_PUBLIC_URL"
   ```

   Windows PowerShell:
   ```powershell
   $env:DATABASE_PUBLIC_URL = 'postgresql://...'
   docker run --rm -v "${PWD}:/backup" postgres:16-alpine `
     pg_dump --format=custom --no-owner --no-privileges `
     --file=/backup/luma-$(Get-Date -Format yyyyMMdd).dump $env:DATABASE_PUBLIC_URL
   ```

3. Guarde o arquivo `.dump` fora do computador também (Google Drive, por exemplo). Ele contém **todos os dados**, inclusive hashes de senha e fotos: trate como confidencial.

Sugestão de rotina: backup antes de cada deploy com migration nova e uma vez por semana.

### 8.2 Testar o backup (restauração local)

Um backup só vale se restaura. Para conferir sem tocar na produção:

```bash
# 1. PostgreSQL vazio e temporário, na porta 55433
docker run -d --rm --name luma-restore -e POSTGRES_DB=luma -e POSTGRES_USER=luma \
  -e POSTGRES_PASSWORD=restore -p 55433:5432 postgres:16-alpine

# 2. Restaurar o arquivo nele
docker run --rm -v "$PWD:/backup" postgres:16-alpine \
  pg_restore --no-owner --no-privileges --exit-on-error \
  --dbname="postgresql://luma:restore@host.docker.internal:55433/luma" /backup/luma-AAAAMMDD.dump

# 3. Conferir
docker exec luma-restore psql -U luma -d luma -c "select count(*) from pieces"

# 4. Descartar
docker stop luma-restore
```

No Linux, troque `host.docker.internal` por `172.17.0.1` ou use `--network host`. Este procedimento foi testado durante a BACK_10: as contagens de todas as tabelas, o conteúdo das fotos e os triggers voltaram idênticos.

### 8.3 Restaurar em produção (emergência)

1. Pare o serviço do backend no Railway (para ninguém gravar durante a restauração).
2. Crie um PostgreSQL novo no Railway e restaure nele com o comando da 8.2, usando a `DATABASE_PUBLIC_URL` do banco novo.
3. Aponte as variáveis do backend (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) para o banco novo e suba o backend.
4. Mantenha o banco antigo até confirmar que está tudo certo.

Restaurar num banco novo, em vez de sobrescrever o atual, deixa sempre um caminho de volta.

## 9. Retenção de dados

| Dado | Quanto tempo fica | Como é apagado |
|---|---|---|
| Chaves de idempotência | Até 7 dias depois de vencer (vencem em 24h) | Job diário às 3h30 |
| Fotos sem peça ligada | 24 horas | Job diário às 4h |
| Auditoria (`audit_logs`) | Indefinidamente | Não é apagada (volume pequeno) |
| Refresh tokens vencidos ou revogados | Indefinidamente | Sem limpeza automática por enquanto (volume pequeno) |
| Peças, lotes, histórico | Indefinidamente | Nunca apagados: são o histórico da loja |
