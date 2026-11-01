# Desenvolvimento

Guia para quem vai rodar e alterar o backend: ambiente local, testes, convenções da API e regras de domínio que o código precisa respeitar.

## Ambiente local

Requisitos: Java 21 e Docker.

1. Copie `.env.example` para `.env` e defina uma senha local para o banco (`DB_PASSWORD`).
2. Suba o PostgreSQL com `docker compose up -d`. `DB_PORT` muda a porta publicada no host.
3. Rode a aplicação com `./gradlew bootRun` (Windows: `gradlew.bat bootRun`).

O perfil padrão é `local`, que lê o banco e o CORS do ambiente. O perfil `prod` não tem credenciais nem valores padrão versionados. A lista completa de variáveis está no [RUNBOOK.md](RUNBOOK.md#2-variáveis-de-ambiente).

Se o Gradle não achar o Java 21, aponte o `JAVA_HOME` só para a sessão do PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"   # caminho do seu JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew bootRun
```

Ou passe o caminho direto: `./gradlew bootRun -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"`.

## Primeiro login local

Nenhum usuário padrão é criado. Para criar o owner local, suba a aplicação uma vez com o bootstrap ligado:

```powershell
docker compose up -d postgres
$env:BOOTSTRAP_OWNER_ENABLED = "true"
$env:BOOTSTRAP_OWNER_NAME = "Owner da loja"
$env:BOOTSTRAP_OWNER_EMAIL = "owner@example.com"
$env:BOOTSTRAP_OWNER_PASSWORD = "<senha-unica-com-pelo-menos-12-caracteres>"
./gradlew bootRun
```

Quando a aplicação terminar de subir, o owner já existe. Pare o processo, desligue o bootstrap e tire a senha do ambiente:

```powershell
$env:BOOTSTRAP_OWNER_ENABLED = "false"
Remove-Item Env:BOOTSTRAP_OWNER_PASSWORD
./gradlew bootRun
```

### Pelo Swagger

Abra `http://localhost:8080/swagger-ui/index.html`, chame `POST /api/v1/auth/login` com o e-mail e a senha e cole o `accessToken` no botão **Authorize**.

O login também grava o cookie de sessão no navegador, mas `refresh` e `logout` respondem `403` pelo Swagger, porque a origem `http://localhost:8080` não está em `CORS_ALLOWED_ORIGINS`. Para testá-los, use curl mandando o cookie sem o header `Origin`:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/refresh --cookie "luma_refresh=<token>"
```

### Pelo frontend

Em `mimo-frontend/.env.local`, configure a URL da API e reinicie o Vite:

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

## Testes

```bash
./gradlew clean check
```

Os testes não precisam de Docker nem de PostgreSQL:

- As regras de negócio são cobertas por testes unitários com JUnit 5 e Mockito, com os repositories mockados.
- Os testes de contexto e de segurança HTTP usam H2 em memória (perfil `test`, sem Flyway).
- Não há Testcontainers nem testes integrados automatizados. Constraints, triggers e migrations são garantidos pelo próprio PostgreSQL, e o CI aplica todas as migrations num banco limpo a cada push ([RUNBOOK.md](RUNBOOK.md#7-ci-github-actions)).

## Convenções da API

### Identificação da requisição

Toda resposta traz `X-Request-Id` e `X-Trace-Id` ([TRACING.md](TRACING.md)). O cliente pode mandar o próprio `X-Request-Id` com 1 a 64 caracteres (letras, dígitos, `.`, `_` ou `-`); valores inválidos são substituídos. O valor também vai para o MDC dos logs.

### Erros

Os erros usam `application/problem+json` com `code`, `requestId` e, quando houver, `fields`. Esses campos são estáveis e o front pode depender deles. Erros internos nunca devolvem a mensagem da exceção nem o stack trace.

### Paginação

Listagens usam `PaginationRequest` e `PageEnvelope`:

- `page` começa em 1 (padrão 1).
- `pageSize` tem padrão 50 e máximo 200.
- Cada endpoint passa a própria lista de campos de ordenação permitidos para `toPageable`, para que o `sort` enviado pelo cliente nunca seja interpolado em SQL.

### Idempotência

Mutações que podem ser repetidas pelo cliente exigem o header `Idempotency-Key`. No serviço de aplicação, chame `IdempotencyService.execute` dentro da transação, passando:

- o ID do usuário autenticado;
- um escopo estável da operação;
- o valor de `Idempotency-Key`;
- o payload da requisição;
- um callback que devolve `IdempotentResponse`.

A chave vale por usuário e operação. Repetir a mesma chave com o mesmo JSON (em forma canônica) devolve o status e o corpo salvos, por 24 horas por padrão. Repetir com um payload diferente responde `409 IDEMPOTENCY_KEY_REUSED`. Uma chave vencida pode ser usada de novo.

O OpenAPI documenta o esquema de autenticação, os erros comuns, a paginação e o header `Idempotency-Key`.

## Regras de domínio

### Vendedoras

- `GET /api/v1/sellers` (owner e staff) aceita `status`, `q`, `page`, `pageSize` e os valores de `sort` permitidos.
- Vendedoras inativas continuam visíveis e filtráveis, para manter o histórico.
- Mutações, estatísticas, comissão e agenda de repasses exigem OWNER. Mutações exigem `Idempotency-Key`.
- Um lote novo só pode ser criado para uma vendedora ativa. Use `SellerApplicationService.requireActiveForConsignment`, que lança o erro `SELLER_INACTIVE`. Um trigger do PostgreSQL também bloqueia inserções e alterações de lote para vendedoras inativas, sem mexer nos lotes e snapshots que já existem.

### Configurações da loja

O Flyway cria as configurações iniciais (não cria usuário):

| Configuração | Valor inicial | Alterável |
|---|---|---|
| Faixa de comissão | 25% a 40%, em passos de 5 pontos | Não |
| Comissão padrão | 30% | Sim |
| Prazos de pagamento disponíveis | 30, 60 e 90 dias | Não (só a seleção dos permitidos) |
| Dias padrão de consignação | 30 | Sim |
| Fuso horário | `America/Sao_Paulo` | Sim |

`PATCH /api/v1/settings` exige OWNER e `Idempotency-Key`.

### Valores e comissão

- Todos os valores em dinheiro são centavos inteiros.
- Os relatórios de comissão usam o `commission_rate_snapshot` do item vendido, e não a taxa atual da vendedora.
- Os filtros `from` e `to` são datas locais inclusivas, no fuso horário configurado da loja.
