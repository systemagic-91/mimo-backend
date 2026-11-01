# Mimo Backend

API da Luma: controla as peças da loja desde o recebimento e a etiquetagem até a consignação com as vendedoras, a venda ou devolução, a comissão e a agenda de repasses.

Java 21, Spring Boot 3.5, Gradle e PostgreSQL 16 (migrations com Flyway).

## Documentação

| Arquivo | Conteúdo |
|---|---|
| [DESENVOLVIMENTO.md](DESENVOLVIMENTO.md) | Rodar localmente, primeiro login, testes, convenções da API e regras de domínio |
| [AUTENTICACAO.md](AUTENTICACAO.md) | Tokens, cookie de sessão, papéis e gestão de usuários |
| [RUNBOOK.md](RUNBOOK.md) | Variáveis de ambiente, primeiro owner, migrations, deploy no Railway, CI, backup e retenção de dados |
| [TRACING.md](TRACING.md) | Como seguir uma requisição pelos logs com `traceId` |
| [METRICAS.md](METRICAS.md) | Métricas técnicas e da loja em `/actuator/metrics` e `/actuator/prometheus` |

## Início rápido

```bash
cp .env.example .env              # defina DB_PASSWORD
docker compose up -d              # PostgreSQL local
./gradlew bootRun                 # Windows: gradlew.bat bootRun
./gradlew clean check             # testes (não precisam de Docker)
```

Na primeira vez é preciso criar o owner: veja [DESENVOLVIMENTO.md](DESENVOLVIMENTO.md#primeiro-login-local).

Com a aplicação no ar:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

## Módulos

Todas as rotas ficam em `/api/v1`.

| Módulo | Rotas | Quem acessa |
|---|---|---|
| Autenticação | `/auth` (login, refresh, logout, me, troca de senha) | Público / autenticado |
| Usuários | `/users` | Owner |
| Vendedoras | `/sellers`, incluindo estatísticas, comissão, agenda de repasses e lotes | Owner e staff (estatísticas, comissão e mutações só owner) |
| Peças | `/pieces` (recebimento, etiquetagem, histórico, custos) | Autenticado (custos só owner) |
| Consignações | `/consignments` (lotes, venda e devolução de itens) | Owner e staff |
| Fotos | `/uploads/piece-photo`, `/photos/{id}` | Upload autenticado, download público |
| Configurações | `/settings` | Leitura owner e staff, alteração owner |
| Dashboard e relatórios | `/dashboard`, `/reports` (giro, atrasos, peças paradas) | Owner |

São públicos apenas o health, a documentação da API, login/refresh/logout e o download de fotos. As métricas exigem owner e o resto exige um JWT válido.
