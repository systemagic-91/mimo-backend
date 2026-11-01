# Autenticação

Como o backend autentica as pessoas, mantém a sessão e controla quem pode fazer o quê.

## Access token (JWT)

- Assinado com HS256 e válido por 15 minutos (`JWT_ACCESS_TOKEN_TTL`).
- Em produção, `JWT_SECRET` precisa ser uma chave aleatória em Base64 com pelo menos 32 bytes (`openssl rand -base64 32`).
- Nos perfis `local` e `test`, se `JWT_SECRET` não estiver definida, uma chave temporária é gerada a cada start: os tokens deixam de valer quando a aplicação reinicia.
- É devolvido no campo `accessToken` do `POST /api/v1/auth/login` e enviado pelo cliente no header `Authorization: Bearer <token>`.

## Refresh token (cookie de sessão)

- É opaco (não é JWT). O banco guarda só o hash SHA-256.
- Viaja só no cookie `luma_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`). O JavaScript não consegue ler esse cookie, e o token nunca aparece no corpo das respostas.
- É trocado por um novo a cada uso (rotação) e revogado no logout ou na troca de senha.
- Se um token já usado for apresentado de novo, todas as sessões ativas daquele usuário são revogadas.
- `POST /api/v1/auth/refresh` e `POST /api/v1/auth/logout` não têm corpo, leem o cookie e respondem `403` se o header `Origin` não estiver em `CORS_ALLOWED_ORIGINS`.
- O logout é público e funciona mesmo com o access token vencido.

Nunca registre tokens nos logs.

## IP real e rate limit de login

O backend roda com `server.forward-headers-strategy: framework`. Atrás dos proxies da Vercel e do Railway, `getRemoteAddr()` devolve o IP real que vem no `X-Forwarded-For`. O rate limit de login conta tentativas por IP e por e-mail (`LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_LIMIT_WINDOW`).

## Papéis e usuários

- **OWNER:** acesso total, inclusive usuários, configurações, custos, comissão, dashboard e relatórios.
- **STAFF:** operação do dia a dia (peças, consignações, consulta de vendedoras e configurações).

O primeiro owner é criado pelo bootstrap ([RUNBOOK.md](RUNBOOK.md#3-primeiro-owner-bootstrap)). Os demais usuários (staff e outros owners) são criados por um owner em `POST /api/v1/users`, com uma senha temporária que a pessoa é obrigada a trocar no primeiro login. Até lá, só `GET /auth/me`, `POST /auth/change-password` e `POST /auth/logout` funcionam; o resto responde `403 PASSWORD_CHANGE_REQUIRED`.

Desativar uma pessoa, mudar o papel dela ou redefinir a senha encerra as sessões dela na hora. A loja nunca fica sem owner ativo: a operação que deixaria isso acontecer responde `422 LAST_OWNER`.
