# Métricas: os números de saúde do sistema

Este documento explica, em linguagem simples, o que são métricas, o que foi feito no backend (BACK_10) e como consultá-las.

## O que são métricas

Logs contam **histórias** ("às 15h02 a Ana vendeu a peça LU-0042"). Métricas contam **números**:

- quantas requisições chegaram no último minuto;
- quanto tempo elas levaram;
- quantas deram erro;
- quanta memória a aplicação está usando;
- quantos lotes foram criados desde que o servidor subiu.

Com números dá para responder rápido "o sistema está saudável?" e perceber tendências, sem ler milhares de linhas de log.

## O que foi feito

### 1. As bibliotecas

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator' // já existia
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'               // novo
```

- **Micrometer** é a "régua" do Spring: ele mede as coisas. Já vem com o Actuator.
- **Prometheus registry** ensina o Micrometer a mostrar os números no formato do **Prometheus**, o padrão de mercado que ferramentas como o Grafana entendem.

Só com isso o Spring Boot já mede sozinho, sem nenhum código nosso:

| Métrica | O que mede |
|---|---|
| `http.server.requests` | Cada requisição HTTP: rota, método, status e duração |
| `jvm.memory.used` | Memória usada pela aplicação |
| `jvm.threads.live` | Threads ativas |
| `hikaricp.connections.active` | Conexões com o banco em uso |
| `process.cpu.usage` | Uso de CPU |

### 2. A configuração

No `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- `include` liga dois endereços novos: `/actuator/metrics` (lista e consulta, em JSON) e `/actuator/prometheus` (tudo de uma vez, no formato do Prometheus).
- `tags.application` carimba todas as métricas com `application="mimo-backend"`. Se um dia houver outro serviço no mesmo painel, dá para separar.
- `percentiles-histogram` guarda a distribuição dos tempos de resposta, não só a média. Assim dá para responder "95% das requisições levaram menos de quanto tempo?" (o famoso **p95**).

### 3. Só o owner vê as métricas

As métricas revelam detalhes internos (rotas, volume de uso, memória), então **não são públicas**. No `SecurityConfiguration`:

```java
.requestMatchers("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
.hasRole("OWNER")
```

| Quem chama | Resposta |
|---|---|
| Sem login | `401` |
| Staff | `403` |
| Owner | `200` com os números |

O `/actuator/health` continua público, porque o Railway precisa dele para saber se o app está vivo, e continua respondendo só `{"status":"UP"}`, sem detalhes.

### 4. Métricas de negócio

Além das técnicas, criamos métricas da **loja**, na classe `shared/metrics/BusinessMetrics.java`:

| Métrica | Tag | Quando conta |
|---|---|---|
| `luma.pieces.movements` | `action=received` ou `labeled` | Peça recebida ou etiquetada |
| `luma.consignments.created` | | Lote criado |
| `luma.consignment.items` | `action=delivered`, `sold` ou `returned` | Peças entregues, vendidas ou devolvidas |
| `luma.photos.uploaded` | | Foto enviada |
| `luma.photos.size` | | Tamanho de cada foto (em bytes) |
| `luma.auth.logins` | `result=success` ou `failure` | Tentativa de login |

Exemplo de como uma métrica é criada (um **Counter** é um contador que só sobe):

```java
public void consignmentItemSold() {
    afterCommit(() -> consignmentItems("sold").increment());
}

private Counter consignmentItems(String action) {
    return Counter.builder("luma.consignment.items")
            .description("Peças entregues, vendidas e devolvidas em consignação")
            .tag("action", action)
            .register(registry);
}
```

E o service só avisa que algo aconteceu:

```java
metrics.consignmentItemSold();
return finishItemAction(actorId, action, "CONSIGNMENT_ITEM_SOLD", ipAddress);
```

#### Por que "afterCommit"?

Uma venda só existe de verdade quando a transação do banco é **confirmada** (commit). Se algo falhar depois de contar, o banco desfaz tudo (rollback), mas o contador já teria subido, e os números mentiriam.

Por isso o `BusinessMetrics` adia a contagem para **depois do commit**:

```java
private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        action.run();                       // fora de transação: conta na hora
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            action.run();                   // só conta se o banco confirmou
        }
    });
}
```

Também conta só **uma vez** por operação: se o front repetir a mesma requisição com a mesma `Idempotency-Key`, o backend devolve a resposta salva sem executar de novo, e o contador não sobe. Isso foi conferido no teste de ponta a ponta.

#### A regra das tags

Tags são as "etiquetas" de uma métrica (`action=sold`). Cada combinação diferente de tags vira uma série separada guardada em memória. Por isso:

- **Pode:** valores fixos e poucos, como `sold`/`returned` ou `success`/`failure`.
- **Não pode:** nome ou ID de vendedora, código de peça, e-mail. Isso criaria milhares de séries (problema chamado de "alta cardinalidade") e ainda vazaria dados pessoais.

## Como consultar

Primeiro faça login como owner e guarde o token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"<sua-senha>"}' | jq -r .accessToken)
```

Listar todas as métricas disponíveis:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/metrics
# {"names":["http.server.requests","jvm.memory.used","luma.auth.logins","luma.pieces.movements", ...]}
```

Ver uma métrica, filtrando por tag:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/actuator/metrics/luma.consignment.items?tag=action:sold"
# {"name":"luma.consignment.items","measurements":[{"statistic":"COUNT","value":12.0}], ...}
```

Ver tudo no formato do Prometheus:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus | grep luma_
# luma_consignment_items_total{action="sold",application="mimo-backend"} 12.0
# luma_auth_logins_total{application="mimo-backend",result="failure"} 3.0
```

Repare que o Prometheus troca `.` por `_` e acrescenta `_total` nos contadores.

## Importante: os números zeram ao reiniciar

As métricas ficam **na memória** do servidor. Quando o app reinicia (novo deploy, por exemplo), os contadores voltam a zero. Elas servem para acompanhar a saúde e o ritmo **agora**, não como histórico. O histórico de verdade continua no banco: relatórios, auditoria e o dashboard.

Para guardar as métricas ao longo do tempo e ver gráficos, o caminho é ligar um coletor (Prometheus ou Grafana Cloud) que leia `/actuator/prometheus` a cada poucos segundos. Como o endpoint exige token de owner, isso vai pedir uma credencial própria para o coletor. Fica para quando for necessário.

## Onde está cada coisa

| Arquivo | O que faz |
|---|---|
| `build.gradle` | Dependência `micrometer-registry-prometheus` |
| `application.yml` | Endpoints expostos, tag `application`, histograma de tempos |
| `config/SecurityConfiguration.java` | Métricas só para owner |
| `shared/metrics/BusinessMetrics.java` | Métricas de negócio e o `afterCommit` |
| `BusinessMetricsTests` | Contagem, commit e rollback |
| `LumaApplicationTests` | `401`/`403`/`200` nos endpoints |
