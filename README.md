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
- A aposta so e aceita se `saldo da carteira - rodadas ainda abertas - sessoes abertas do Flecha Certeira >= valor`
  (consulta sincrona ao wallet-service).
- Um agendador (1s) fecha as rodadas vencidas com o ultimo preco recebido e publica `ordem.executada`.

Padroes: **Strategy** (`estrategia/`: uma classe por tipo de aposta) e **Factory** (`fabrica/OrdemFactory`).

## Flecha Certeira (via gateway: `/api/jogos/flecha`)

Sessoes do jogo de arco e flecha do frontend. O servidor e a fonte da verdade do dinheiro: cobra a entrada e as
flechas, confere cada tiro e, ao encerrar, manda o resultado para a carteira. As tabelas ficam em
`flecha/regras/` (`Campo`, `Passaro`, `TipoFlecha`) e espelham `src/regras.js` do frontend.

| Metodo | Caminho | Descricao |
|---|---|---|
| GET | `/jogos/flecha/regras` | Campos, passaros, flechas e limites. **Publico** (nao precisa de `X-Usuario-Id`) |
| POST | `/jogos/flecha/sessoes` | Abre uma sessao. Corpo: `{"campo":"bosque","flechas":{"wood":10,"iron":5}}` → **201** |
| GET | `/jogos/flecha/sessoes/atual` | Sessao ABERTA do jogador (**200**) ou nenhuma (**204**) |
| POST | `/jogos/flecha/sessoes/{id}/tiros` | Um tiro: `{"flecha":"gold","acertos":["red","big"]}` (passaros atravessados por esta flecha, na ordem) → `{"ganhosTiro":n,"sessao":{...}}` |
| POST | `/jogos/flecha/sessoes/{id}/flechas` | Compra mais flechas: `{"flecha":"iron","quantidade":5}` |
| POST | `/jogos/flecha/sessoes/{id}/encerrar` | Encerra e publica o resultado (idempotente: se ja encerrada, so devolve) |

A sessao volta como `{ id, campo, status (ABERTA|ENCERRADA), custo, ganhos, resultado (= ganhos - custo),
flechas (restantes por tipo), compradas, disparadas, acertos, criadaEm, encerradaEm }`.

Tabelas:

| Campo (`slug`) | Entrada | Flecha base | Mult | Pesos red/big/yellow/blue/gold |
|---|---|---|---|---|
| `vilarejo` | 5 | 1 | 2 | 5 / 3 / 1 / 0 / 0,3 |
| `bosque` | 15 | 3 | 4 | 4 / 2 / 3 / 1 / 0,5 |
| `pico` | 40 | 8 | 10 | 3 / 1 / 3 / 3 / 0,8 |
| `reserva-real` | 100 | 20 | 25 | 2 / 1 / 3 / 4 / 1,2 |

Passaros (moedas base): `red` 2, `big` 1, `yellow` 4, `blue` 5, `gold` 15.
Flechas (preco x / moedas x): `wood` 1/1, `iron` 2/2,5, `gold` 4/5,5, `diamond` 8/12.

Regras:

- Custo da sessao = entrada do campo + flechas (cada flecha custa `fatorPreco x flecha base do campo`).
- Valor do passaro no campo = `round(moedas x mult)`; moedas de um acerto =
  `round(valor do passaro x fatorMoedas da flecha) x posicao no combo` (1o passaro da flecha x1, 2o x2, ...).
  Arredondamento meio-para-cima, igual ao `Math.round` do frontend (ex.: vilarejo + red + gold = 22;
  reserva-real, passaro gold = 375).
- Abrir: campo conhecido, quantidades >= 0, de 1 a 40 flechas. Uma sessao ABERTA por jogador (**409**; garantido
  tambem por um UNIQUE no banco). O custo precisa caber no **saldo disponivel** = saldo da carteira
  (`GET /carteira` no wallet-service) - ordens abertas - sessoes abertas, senao **422**
  `Saldo insuficiente: disponivel X`. As ordens de `/ordens` tambem passaram a descontar as sessoes abertas.
- Tiro: sessao de outro jogador **404**, ja encerrada **409**; sem flecha daquele tipo, mais de 5 passaros ou
  passaro com peso zero no campo **422**; menos de **150 ms** desde o tiro anterior **429**.
- Compra: 1 a 20 flechas por vez, aljava com no maximo 40 restantes, saldo disponivel para o custo extra (**422**).
- Encerrar publica em `tigrinho.eventos`/`ordem.executada` o mesmo JSON que a carteira ja consome:
  `{ ordemId: id da sessao, usuarioId, simbolo: slug do campo, tipo: "FLECHA_CERTEIRA", valor: custo,
  resultado: GANHOU|PERDEU|EMPATOU, valorLiquido: ganhos - custo, executadaEm }` (+ `ganhos` e `acertos`, que a
  carteira ignora). A carteira e idempotente por `ordemId`.
- Sessoes ABERTAS sem atividade ha mais de `flecha.expira-apos` sao encerradas por um agendador (a cada 60 s).
- Duas alteracoes simultaneas na mesma sessao (`@Version`) → **409**, o cliente tenta de novo.

**Limitacao conhecida (anti-trapaca):** e o cliente que informa quais passaros cada flecha acertou. O servidor so
valida a **plausibilidade** (tem a flecha, combo ate 5, passaro existe no campo, ritmo minimo entre tiros); um
cliente adulterado ainda pode declarar acertos que nao aconteceram, dentro desses limites. A re-simulacao completa
dos tiros no servidor (posicoes, sementes do sorteio, fisica) fica para antes de trocar o sandbox pelo Pix real.

Configuracao:

| Propriedade | Variavel | Padrao | O que faz |
|---|---|---|---|
| `flecha.expira-apos` | `FLECHA_EXPIRA_APOS` | `10m` | Inatividade que encerra a sessao automaticamente |
| `flecha.intervalo-minimo-tiro` | — | `150ms` | Menor intervalo entre tiros (senao 429) |
| `flecha.agendador.habilitado` | `FLECHA_AGENDADOR_HABILITADO` | `true` | Liga o encerramento automatico (desligado nos testes) |
| `flecha.agendador.intervalo-ms` | — | `60000` | Intervalo entre verificacoes |

## Rodando

```bash
mvn verify            # testes + relatorio Jacoco (target/site/jacoco); falha abaixo de 80% de linhas ou 75% de ramos
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
