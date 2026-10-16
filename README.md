# Mimo Backend

Backend base para a API Luma, usando Java 21, Spring Boot 3, Gradle e PostgreSQL.

## Desenvolvimento local

1. Copie o arquivo `.env.example` para `.env` e defina uma senha local do banco.
2. Inicie o PostgreSQL com `docker compose up -d`.
3. Execute a aplicação com `./gradlew bootRun` (Windows: `gradlew.bat bootRun`).
4. Execute os testes com `./gradlew clean test` (Windows: `gradlew.bat clean test`).

O perfil padrão `local` lê as configurações do banco e do CORS a partir do ambiente. Os testes de contexto usam H2; os testes de integração com PostgreSQL iniciam o PostgreSQL 16 com Testcontainers quando o Docker estiver disponível. Para usar um banco de teste descartável já existente, defina `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` e `POSTGRES_TEST_PASSWORD` antes de executar `./gradlew test`. As migrações do Flyway rodam contra esse banco, então não aponte essa configuração para dados que você precise manter. `DB_PORT` altera a porta host publicada pelo Compose.

As configurações do banco e do CORS de produção não possuem credenciais ou valores padrão versionados.

## Autenticação

Os JWTs de acesso usam HS256 com tempo de vida padrão de 15 minutos. Defina `JWT_SECRET` como uma chave aleatória em Base64 com pelo menos 32 bytes em produção; os perfis local/teste geram uma chave efêmera quando ela não está definida, então os tokens deixam de funcionar após reinicialização. Os refresh tokens são opacos, armazenados apenas como hashes SHA-256, rotacionados no uso e revogados no logout, na troca de senha ou na detecção de reutilização. Um token reutilizado revoga todas as sessões de refresh ativas daquele usuário.

A API retorna o refresh token no corpo da resposta para seguir o contrato atual. O frontend atual o mantém em `sessionStorage`; como o JavaScript consegue lê-lo, isso é um compromisso de desenvolvimento e deve ser substituído por um fluxo com cookie HttpOnly e contrato de CSRF/CORS antes da produção. Nunca registre tokens nos logs.

Para provisionar o primeiro owner, defina `BOOTSTRAP_OWNER_ENABLED=true`, `BOOTSTRAP_OWNER_NAME`, `BOOTSTRAP_OWNER_EMAIL` e uma `BOOTSTRAP_OWNER_PASSWORD` de uso único com pelo menos 12 caracteres, e inicie a aplicação uma vez. O bootstrap recusa sobrescrever uma conta existente; remova as credenciais e desative a flag imediatamente depois.

### Primeiro owner

No Windows PowerShell, inicie o banco e configure uma senha escolhida localmente; nenhum login/senha padrão é criado ou versionado:

```powershell
docker compose up -d postgres
$env:BOOTSTRAP_OWNER_ENABLED = "true"
$env:BOOTSTRAP_OWNER_NAME = "Owner da loja"
$env:BOOTSTRAP_OWNER_EMAIL = "owner@example.com"
$env:BOOTSTRAP_OWNER_PASSWORD = "<senha-unica-com-pelo-menos-12-caracteres>"
./gradlew bootRun
```

Caso você não tenha o `JAVA_HOME` configurado, pode definir uma variável de ambiente temporária no PowerShell:

```powershell
$env:JAVA_HOME = "C:\sdkman\candidates\java\21.0.5-amzn\bin" # substitua pelo caminho do seu JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew bootRun
```

Ou usar: `./gradlew bootRun -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"`.

Após a aplicação confirmar a inicialização, o owner já foi criado. Interrompa o processo, desative o bootstrap e remova a senha do ambiente antes de reiniciar:

```powershell
$env:BOOTSTRAP_OWNER_ENABLED = "false"
Remove-Item Env:BOOTSTRAP_OWNER_PASSWORD
./gradlew bootRun
```

Para entrar pelo Swagger, abra `http://localhost:8080/swagger-ui/index.html`, execute `POST /api/v1/auth/login` com o e-mail e a senha escolhidos acima e use o `accessToken` retornado no botão **Authorize**. O Swagger enviará o token de acesso às rotas privadas.

Para a tela de login do frontend, configure `VITE_API_BASE_URL=http://localhost:8080/api/v1` em `mimo-frontend/.env.local`, reinicie o Vite e entre com as mesmas credenciais:

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

O frontend atual persiste o refresh token em `sessionStorage`; não use credenciais reais de produção nesse fluxo até a troca por cookie HttpOnly.

O health check está disponível em `/actuator/health`; a OpenAPI está em `/v3/api-docs` e a Swagger UI em `/swagger-ui/index.html`. Apenas o endpoint de health e a documentação da API são públicos; as demais rotas exigem um JWT válido.

## Contratos HTTP

Toda resposta inclui `X-Request-Id`. Os clientes podem enviar um identificador com 1 a 64 caracteres usando letras, dígitos, `.`, `_` ou `-`; valores inválidos são substituídos. O valor também é colocado no MDC de logging. Os erros usam `application/problem+json` com `code`, `requestId` e `fields` opcionais estáveis; erros internos nunca incluem mensagens de exceção ou stack traces na resposta.

Os endpoints de listagem devem usar `PaginationRequest` e `PageEnvelope`: `page` é base 1, padrão 1; `pageSize` tem padrão 50 e é limitado a 200. Cada endpoint fornece sua própria lista permitida a `toPageable`, para que valores de ordenação enviados pelo cliente nunca sejam interpolados em SQL.

Para mutações reexecutáveis, chame `IdempotencyService.execute` dentro da transação do serviço de aplicação com o ID do usuário autenticado, um escopo estável de operação, o cabeçalho `Idempotency-Key`, o payload da requisição e um callback retornando `IdempotentResponse`. A chave é escopada por usuário e operação. JSON canônico idêntico replaya o status/corpo HTTP salvo por 24 horas por padrão; reutilizar a chave com um payload diferente retorna `409 IDEMPOTENCY_KEY_REUSED`. Chaves expiradas podem ser reivindicadas novamente. O documento OpenAPI descreve o esquema de autenticação, erros comuns, paginação e o componente do cabeçalho `Idempotency-Key`.

## Vendedores e configurações da loja

`GET /api/v1/sellers` está disponível para owner/staff e aceita `status`, `q`, `page`, `pageSize` e valores de `sort` permitidos. Vendedores inativos permanecem visíveis e filtráveis para histórico. Mutações de vendedor, estatísticas e relatórios de comissão exigem OWNER; mutações exigem `Idempotency-Key`. `GET /api/v1/sellers/{id}/consignments` está disponível para owner/staff.

O Flyway alimenta as configurações da loja, não uma conta de owner: comissão de 25-40% em passos de 5 pontos, padrão 30%, termos de pagamento `[30, 60, 90]`, 30 dias padrão de consignação e `America/Sao_Paulo`. A faixa de comissão e as opções de termos de pagamento são fixas nesta versão; `PATCH /api/v1/settings` altera apenas a taxa padrão, os termos permitidos selecionados, os dias padrão e o fuso horário. Alterações nas configurações exigem OWNER e `Idempotency-Key`.

As estatísticas de vendedores e os totais de comissão são em centavos inteiros. Os relatórios de comissão usam o `commission_rate_snapshot` do item vendido, não a taxa atual do vendedor; `from` e `to` são datas locais inclusivas no fuso horário configurado da loja. Novas consignações devem usar vendedores ativos: chame `SellerApplicationService.requireActiveForConsignment` para obter o erro de aplicação `SELLER_INACTIVE`. Um trigger do PostgreSQL bloqueia independentemente inserções ou alterações de vendedor em vendedores inativos, preservando consignações e snapshots existentes quando um vendedor é inativado.
