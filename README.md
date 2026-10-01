# trading-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Motor de ordens/rodadas do jogo. Recebe pedido de entrada ("aposta") vindo do frontend via API Gateway (REST sincrono), calcula o resultado usando o preco mais recente publicado pelo `market-data-service`, e publica o evento `ordem.executada` na fila para os demais servicos consumirem. Concentra os padroes Strategy (tipos de aposta/ordem) e Factory (criacao de rodada/ordem).

## Como interage com os outros servicos

- Consome (assincrono, via fila): eventos de preco publicados pelo `market-data-service`.
- Publica (assincrono): evento `ordem.executada`, consumido por `wallet-service` e `notification-service`.
- Exposto (sincrono, via API Gateway): endpoint de criacao de ordem e consulta de status.

## API (via gateway: `/api/ordens`)

O jogador e identificado pelo cabecalho `X-Usuario-Id` (o api-gateway vai preencher a partir do JWT do Auth0 na Etapa 3).

| Metodo | Caminho | Descricao |
|---|---|---|
| POST | `/ordens` | Abre uma rodada. Corpo: `{"simbolo":"BTCUSDT","tipo":"ALTA","valor":100,"duracaoSegundos":30}` |
| GET | `/ordens` | Rodadas do jogador, mais recentes primeiro |
| GET | `/ordens/{id}` | Uma rodada (404 se for de outro jogador) |
| GET | `/ordens/regras` | Modos, multiplicadores, duracoes e limites (nao precisa de `X-Usuario-Id`) |

Regras do jogo:

- `modo` (opcional, padrao `DIFICIL`) define o risco da rodada:

  | Modo | ALTA/BAIXA pagam | LATERAL paga | Derrota leva | ALTA/BAIXA so ganham se o preco andar | LATERAL ganha se variar no maximo |
  |---|---|---|---|---|---|
  | `FACIL` | 1,50x | 1,80x | metade da aposta | qualquer movimento | 0,10% |
  | `DIFICIL` | 1,90x | 2,50x | a aposta toda | qualquer movimento | 0,05% |
  | `INSANO` | 4,00x | 6,00x | a aposta toda | pelo menos 0,02% | 0,01% |

- Preco igual em ALTA/BAIXA nos modos FACIL e DIFICIL = empate (devolve a aposta); no INSANO e derrota.
- `BARREIRA` ("sem toque", usada pelos jogos do frontend): o pedido leva um `alvo`
  (`{"simbolo":"BTCUSDT","tipo":"BARREIRA","alvo":83600.5,"valor":10,"duracaoSegundos":30}`) e a rodada ganha se o
  preco **nao encostar** no alvo ate o fim. O toque e checado a cada `preco.atualizado` e derruba a rodada na hora.
  O multiplicador vem da distancia ate o alvo e da volatilidade medida do ativo (`CalculadoraBarreira`): a chance de
  nao tocar e `erf(z/raiz(2))`, com `z = distancia / (volatilidade * raiz(duracao))`, e a aposta devolve 95% do valor
  justo, limitada a 20x. Alvo colado no preco (menos de 0,001%) ou longe demais (menos de 1,10x) e recusado.
  `GET /ordens/barreira` devolve esses parametros e a volatilidade atual de cada ativo.
- `duracaoSegundos`: 15, 30, 60 ou 300. `valor`: de 1,00 a 10.000,00.
- A aposta so e aceita se `saldo da carteira - rodadas ainda abertas >= valor` (consulta sincrona ao wallet-service).
- Um agendador (1s) fecha as rodadas vencidas com o ultimo preco recebido e publica `ordem.executada`.

Padroes: **Strategy** (`estrategia/`: uma classe por tipo de aposta) e **Factory** (`fabrica/OrdemFactory`).

## Rodando

```bash
mvn verify            # testes + relatorio Jacoco (target/site/jacoco); falha abaixo de 80% de linhas
```

Pra subir com as dependencias (Postgres, RabbitMQ, demais servicos) use o `docker-compose.yml` do repo `api-gateway`.

Variaveis: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_HOST`, `MARKET_DATA_URL`, `WALLET_URL`.

## Repositorios do projeto

- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [api-gateway](https://github.com/tigrinho-trader/api-gateway)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
