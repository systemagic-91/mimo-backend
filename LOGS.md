# Logs: o que o backend registra e como ligar e desligar

Este documento explica, em linguagem simples, o que aparece no painel de logs do Railway, como deixar os logs no mínimo para economizar recursos e como ligar tudo quando for preciso investigar um problema (BACK_28).

## A ideia em uma frase

No dia a dia o backend registra **quase nada**: só os erros inesperados, os avisos das bibliotecas e **todas as vendas**. Quando alguém relata um problema, você liga o modo completo pelo Actuator, sem reiniciar, investiga e volta ao normal.

## Os quatro grupos de log

Cada linha de log sai por um "logger", que é como uma torneira com nome próprio. Cada torneira tem um nível, e só sai o que for daquele nível para cima (`DEBUG` < `INFO` < `WARN` < `ERROR`; `OFF` fecha tudo).

| Logger | O que registra | Padrão | Variável |
|---|---|---|---|
| `br.com.luma` | eventos do backend: login, peças, lotes, mudanças de cadastro, erros | `ERROR` | `LOG_LEVEL_APP` |
| `br.com.luma.access` | uma linha por requisição (método, caminho, status, duração) | `OFF` | `LOG_LEVEL_ACCESS` |
| `root` | bibliotecas: Spring, Hibernate, Tomcat | `WARN` | `LOG_LEVEL_ROOT` |
| `br.com.luma.sales` | **as vendas** | `INFO`, sempre | nenhuma (fixo) |

Há ainda o `br.com.luma.loglevel`, ligado em `INFO`, que registra quem mudou um nível pelo Actuator (veja abaixo).

### Por que as vendas não desligam

Decisão da loja: toda venda precisa aparecer no log. O logger `br.com.luma.sales` tem nível próprio no `application.yml`, sem variável de ambiente, e o Actuator recusa mudá-lo (`403`). Como ele tem nível próprio, não "herda" o de `br.com.luma`: mesmo com o resto do backend em `OFF`, as vendas continuam saindo. Para desligar seria preciso mudar o código, de propósito.

O log não substitui o banco: a venda continua gravada no lote, no histórico da peça e na auditoria. O log serve para acompanhar pelo Railway, e some conforme a retenção do plano.

## Modos prontos

| Modo | `LOG_LEVEL_APP` | `LOG_LEVEL_ACCESS` | `LOG_LEVEL_ROOT` | O que sai, além das vendas |
|---|---|---|---|---|
| **Padrão (econômico)** | `ERROR` | `OFF` | `WARN` | erros inesperados (500), falhas dos jobs e avisos das bibliotecas |
| Problemas | `WARN` | `WARN` | `WARN` | também recusas de segurança, conflitos e requisições lentas |
| Completo (investigar) | `INFO` | `INFO` | `INFO` | tudo o que está na lista de eventos abaixo |
| Desligado | `OFF` | `OFF` | `OFF` | nada além das vendas. Não recomendado: um erro 500 passa sem rastro |

Por que o padrão não é `OFF`: quando uma requisição quebra, quem registra o erro é o próprio backend (`br.com.luma`), e não o servidor. Com `OFF`, o erro sumiria, e ligar depois só pega o **próximo**. Em `ERROR` o custo é quase zero, porque só sai linha quando algo quebra.

## Como trocar de modo

### Jeito 1: variáveis no Railway (fica valendo)

1. No Railway, abra o serviço da API → **Variables**.
2. Crie ou edite `LOG_LEVEL_APP`, `LOG_LEVEL_ACCESS` e `LOG_LEVEL_ROOT` com os valores do modo desejado.
3. Salve e aplique. **O serviço reinicia (cerca de um minuto fora do ar).**

Use este jeito para escolher o modo de todos os dias. Sem as variáveis, vale o padrão econômico.

### Jeito 2: Actuator (na hora, sem reiniciar)

Para investigar algo agora, sem derrubar o serviço. Precisa do access token de um owner (o `accessToken` da resposta do login).

Ligar o modo completo:

```bash
API=https://mimo-backend-production-2b7f.up.railway.app
TOKEN=<accessToken do owner>

curl -X POST "$API/actuator/loggers/br.com.luma" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'

curl -X POST "$API/actuator/loggers/br.com.luma.access" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'
```

Ver o nível atual:

```bash
curl "$API/actuator/loggers/br.com.luma" -H "Authorization: Bearer $TOKEN"
```

Voltar ao valor das variáveis (`null` = "esquece o que eu mudei"):

```bash
curl -X POST "$API/actuator/loggers/br.com.luma" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"configuredLevel":null}'
```

- A mudança vale até o próximo restart ou deploy; depois volta o que está nas variáveis.
- Só owner pode usar (`401` sem login, `403` para staff).
- `POST /actuator/loggers/br.com.luma.sales` sempre responde `403`.
- Cada mudança gera uma linha `Log level changed logger=… level=…` com o `userId` de quem mudou, para não ficar um "buraco" sem explicação.

## O que aparece em cada evento

Todas as linhas trazem, automaticamente, `requestId`, `traceId` e, quando há login, `userId` e `role` (em produção, como campos do JSON). Para achar tudo de uma requisição, busque o `requestId` (ele também volta no header `X-Request-Id` da resposta). Mais sobre isso em [TRACING.md](TRACING.md).

| Evento | Nível | Logger |
|---|---|---|
| `Sale registered consignmentId=… consignmentCode=… pieceId=… pieceCode=… sellerId=… valueCents=… commissionRate=… soldAt=… retroactive=…` | INFO | `br.com.luma.sales` |
| `HTTP request method=… path=… status=… durationMs=…` (com `slow=true` em WARN acima de `LOG_SLOW_REQUEST_MS`) | INFO/WARN | `br.com.luma.access` |
| `Login succeeded`, `Logout`, `Password changed … forced=…` | INFO | `br.com.luma` |
| `Login rejected reason=… email=l***@… ip=…`, `Login rate limit reached`, `Refresh token reuse detected…`, `Password change rejected`, `Origin rejected` | WARN | `br.com.luma` |
| `User created / updated / role changed / deactivated / reactivated / password reset` (só IDs e nomes de campo) | INFO | `br.com.luma` |
| `Seller created / updated / status changed`, `Settings updated changedFields=…`, `Store timezone changed` | INFO | `br.com.luma` |
| `Piece received / labeled / costs updated` (custos sem valores), `Photo uploaded` | INFO | `br.com.luma` |
| `Photo rejected reason=TOO_LARGE`, `Photo upload rate limit reached` | WARN | `br.com.luma` |
| `Consignment created / item returned / settled` | INFO | `br.com.luma` |
| `Idempotent response replayed` (INFO); `Idempotency key reused…`, `…still in progress` (WARN) | INFO/WARN | `br.com.luma` |
| `API error status=… code=… path=…` (400/404 em INFO com os nomes dos campos; 401/403/409/422/429 em WARN) | INFO/WARN | `br.com.luma` |
| `Unhandled request failure path=…` com a stack trace (erro 500) | ERROR | `br.com.luma` |
| Limpezas diárias: `… deleted count=… durationMs=…` (INFO); falha (ERROR) | INFO/ERROR | `br.com.luma` |
| `Log level changed logger=… level=…` | INFO | `br.com.luma.loglevel` |

As linhas de negócio só saem **depois que a transação confirma**: uma venda que falhou no meio não aparece.

## O que nunca aparece

- Senhas (inclusive as temporárias) e hashes de senha.
- Access token, refresh token, o cookie de sessão e o header `Authorization`.
- Corpo das requisições e respostas, e as fotos.
- Nome e telefone de vendedoras e usuários; o e-mail só aparece mascarado (`l***@email.com`).
- O que a pessoa digitou na busca (a linha de acesso não leva a query) e os valores recusados numa validação (só o nome do campo).

Os testes `AuthApplicationServiceTests`, `SellerApplicationServiceTests` e `LoggingSupportTests` conferem isso.

## Rodando localmente

O padrão econômico vale também na sua máquina. Para ver tudo enquanto desenvolve:

```powershell
$env:LOG_LEVEL_APP = "INFO"; $env:LOG_LEVEL_ACCESS = "INFO"; .\gradlew.bat bootRun
```

No perfil local as linhas são texto, com `[requestId:… traceId:… userId:… role:…]`; no perfil `prod` (Railway) saem em JSON.
