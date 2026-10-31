# Tracing: seguindo uma requisição do começo ao fim

Este documento explica, em linguagem simples, o que é tracing, o que foi feito no backend (BACK_10) e como usar no dia a dia.

## O problema que o tracing resolve

Imagine que alguém da loja diz: *"Tentei vender a peça LU-0042 às 15h e deu erro"*. O servidor atende várias pessoas ao mesmo tempo, então o log fica cheio de linhas misturadas de requisições diferentes. Sem uma "etiqueta" em comum, é muito difícil achar quais linhas pertencem àquela venda.

O tracing coloca essa etiqueta. Cada requisição recebe um **número único** e esse número aparece em **toda linha de log** que ela produzir. Basta buscar o número para ver só o que aconteceu naquela requisição.

## Dois conceitos: trace e span

- **Trace** (rastro): é a requisição inteira, do momento em que chega ao servidor até a resposta. O `traceId` é o "número do protocolo" dela, com 32 caracteres hexadecimais, por exemplo `4bf92f3577b34da6a3ce929d0e0e4736`.
- **Span** (trecho): é uma etapa dentro do trace. A requisição HTTP é um span; se no futuro medirmos uma consulta ao banco separadamente, ela vira outro span "filho". O `spanId` identifica a etapa atual.

Uma comparação: o trace é a viagem completa de uma encomenda; os spans são cada parada (coleta, centro de distribuição, entrega). O `traceId` é o código de rastreio que vale para todas as paradas.

## O que foi feito

### 1. A biblioteca

No `build.gradle` entrou uma dependência:

```groovy
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
```

- **Micrometer Tracing** é a forma oficial do Spring Boot 3 de fazer tracing.
- **bridge-otel** significa que, por baixo, quem gera os IDs é o **OpenTelemetry**, o padrão aberto de mercado. Isso deixa a porta aberta para mandar os rastros para uma ferramenta visual no futuro (veja o final deste documento).

Só com essa dependência o Spring Boot já cria um span para cada requisição HTTP e coloca `traceId` e `spanId` no **MDC**, uma "mochila" de informações que acompanha a thread da requisição e que o sistema de logs consegue ler.

### 2. A configuração

No `application.yml`:

```yaml
management:
  tracing:
    sampling:
      probability: ${TRACING_SAMPLING_PROBABILITY:1.0}

logging:
  pattern:
    correlation: ""
    level: "%5p [requestId:%X{requestId:-} traceId:%X{traceId:-} spanId:%X{spanId:-}]"
```

- `probability: 1.0` quer dizer "rastrear 100% das requisições". Como os rastros não são enviados para lugar nenhum (só viram IDs no log), o custo é muito baixo. Se um dia houver um exportador e muito tráfego, dá para baixar, por exemplo para `0.1` (10%), pela variável `TRACING_SAMPLING_PROBABILITY`.
- `%X{traceId:-}` lê o `traceId` da "mochila" (MDC) e escreve na linha de log. O `:-` significa "se não houver, escreva vazio", o que acontece em linhas que não pertencem a uma requisição, como as do início da aplicação.
- `correlation: ""` desliga o bloco de correlação que o Spring Boot adicionaria por conta própria; sem isso, os IDs apareceriam duas vezes em cada linha.

Uma linha de log local fica assim:

```text
DEBUG [requestId:req_b922... traceId:eaf6a1721d83cc8c60b619ffbd879333 spanId:5577517db567c4f0] ... Completed 200 OK
```

Em produção os logs saem em JSON (perfil `prod`, `logging.structured.format.console: logstash`), e os IDs viram campos próprios:

```json
{"@timestamp":"2026-10-31T15:02:11.420-03:00","level":"ERROR","message":"Unhandled request failure ...",
 "requestId":"req_b922...","traceId":"eaf6a1721d83cc8c60b619ffbd879333","spanId":"5577517db567c4f0"}
```

### 3. O `traceId` na resposta

Para quem usa o sistema conseguir informar o número da requisição, criamos o filtro `TraceIdResponseFilter`. Ele pega o span atual e devolve o `traceId` no header `X-Trace-Id` de **toda** resposta:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null) {
            response.setHeader(HEADER_NAME, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
```

Por que `@Order(HIGHEST_PRECEDENCE + 2)`? Os filtros rodam em fila. O filtro do próprio Spring que **cria** o span roda na posição `+1`; o nosso precisa vir **depois** dele, senão ainda não existiria span para ler.

O header também foi liberado no CORS (`WebConfiguration`), para que o JavaScript do front consiga lê-lo:

```java
configuration.setExposedHeaders(List.of("Location", "X-Request-Id", "X-Trace-Id"));
```

## `requestId` e `traceId`: por que dois?

O `X-Request-Id` já existia desde a BACK_04 e continua igual: o cliente pode mandar o próprio ID e ele também aparece nos erros (`ProblemDetail.requestId`). O `traceId` é gerado pelo padrão OpenTelemetry e é o que ferramentas de tracing entendem. Manter os dois não custa nada e cada um tem seu uso:

| | `X-Request-Id` | `X-Trace-Id` |
|---|---|---|
| Quem cria | O cliente (opcional) ou o backend | Sempre o backend (OpenTelemetry) |
| Aparece nos erros (`ProblemDetail`) | Sim | Não |
| Entendido por ferramentas de tracing | Não | Sim |

## Como usar no dia a dia

1. Aconteceu um erro. Peça o `requestId` da mensagem de erro, ou abra o DevTools do navegador → aba **Network** → clique na requisição → **Response Headers** → `X-Trace-Id`.
2. No Railway, abra **Deployments → View Logs** e cole o ID na busca.
3. Aparecem só as linhas daquela requisição, em ordem.

Testando localmente pelo terminal:

```bash
curl -i http://localhost:8080/actuator/health
# HTTP/1.1 200
# X-Request-Id: req_6f1c...
# X-Trace-Id: 9a3c5d0e8b7f41f2a1c6d4e2b8f0a7c3
```

## Cuidados com privacidade

- O `traceId` é só um número aleatório: não contém dados da pessoa, senha nem token.
- Nunca colocamos dados pessoais (nome, e-mail, telefone) em spans ou no MDC.

## E se um dia quisermos ver os rastros num gráfico?

Hoje os rastros não saem do servidor; só os IDs vão para o log. Para ver uma "linha do tempo" de cada requisição numa ferramenta (Grafana Tempo, Jaeger, Honeycomb...), basta:

1. adicionar o exportador OTLP:
   ```groovy
   runtimeOnly 'io.opentelemetry:opentelemetry-exporter-otlp'
   ```
2. configurar o endereço da ferramenta:
   ```yaml
   management:
     otlp:
       tracing:
         endpoint: https://<endereco-da-ferramenta>/v1/traces
   ```

Nenhuma linha do código da aplicação precisa mudar. Foi por isso que escolhemos o caminho Micrometer + OpenTelemetry.

## Onde está cada coisa

| Arquivo | O que faz |
|---|---|
| `build.gradle` | Dependência `micrometer-tracing-bridge-otel` |
| `application.yml` | Taxa de amostragem e formato do log com `traceId`/`spanId` |
| `application-prod.yml` | Logs em JSON em produção |
| `shared/web/TraceIdResponseFilter.java` | Header `X-Trace-Id` em toda resposta |
| `config/WebConfiguration.java` | Libera o header no CORS |
| `TraceIdResponseFilterTests` e `LumaApplicationTests` | Testes do header |
