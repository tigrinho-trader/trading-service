# trading-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Motor das rodadas do jogo (no codigo, "ordens") e do estudo sobre previsao de precos. Recebe o pedido de rodada (a previsao da direcao do preco feita pelo jogador) vindo do frontend via API Gateway (REST sincrono), calcula o resultado usando o preco mais recente publicado pelo `market-data-service`, e publica o evento `ordem.executada` na fila para os demais servicos consumirem. Tambem guarda as sessoes do Flecha Certeira e as operacoes do estudo feitas com as flechas de mercado. Concentra os padroes Strategy (tipos de previsao/ordem) e Factory (criacao de rodada/ordem).

## Como interage com os outros servicos

- Consome (assincrono, via fila): eventos de preco publicados pelo `market-data-service`.
- Publica (assincrono): evento `ordem.executada`, consumido por `wallet-service` e `notification-service`.
- Exposto (sincrono, via API Gateway): endpoints de criacao e consulta de rodadas (`/ordens`), do Flecha Certeira e do estudo.

## API (via gateway: `/api/ordens`)

O jogador e identificado pelo cabecalho `X-Usuario-Id` (o api-gateway vai preencher a partir do JWT do Auth0 na Etapa 3).

| Metodo | Caminho | Descricao |
|---|---|---|
| POST | `/ordens` | Abre uma rodada. Corpo: `{"simbolo":"BTCUSDT","tipo":"ALTA","valor":100,"duracaoSegundos":30}` |
| GET | `/ordens` | Rodadas do jogador, mais recentes primeiro |
| GET | `/ordens/{id}` | Uma rodada (404 se for de outro jogador) |
| GET | `/ordens/regras` | Modos, multiplicadores, duracoes e limites (nao precisa de `X-Usuario-Id`) |

Regras das rodadas:

- `modo` (opcional, padrao `DIFICIL`) define a dificuldade da rodada:

  | Modo | Multiplicador ALTA/BAIXA | Multiplicador LATERAL | Previsao errada desconta | ALTA/BAIXA so acertam se o preco andar | LATERAL acerta se variar no maximo |
  |---|---|---|---|---|---|
  | `FACIL` | 1,50x | 1,80x | metade do valor | qualquer movimento | 0,10% |
  | `DIFICIL` | 1,90x | 2,50x | o valor todo | qualquer movimento | 0,05% |
  | `INSANO` | 4,00x | 6,00x | o valor todo | pelo menos 0,02% | 0,01% |

- Preco igual em ALTA/BAIXA nos modos FACIL e DIFICIL = empate (devolve o valor); no INSANO conta como erro.
- `BARREIRA` ("sem toque", usada pelos jogos do frontend): o pedido leva um `alvo`
  (`{"simbolo":"BTCUSDT","tipo":"BARREIRA","alvo":83600.5,"valor":10,"duracaoSegundos":30}`) e a previsao acerta se o
  preco **nao encostar** no alvo ate o fim. O toque e checado a cada `preco.atualizado` e encerra a rodada como erro na hora.
  O multiplicador vem da distancia ate o alvo e da volatilidade medida do ativo (`CalculadoraBarreira`): a chance de
  nao tocar e `erf(z/raiz(2))`, com `z = distancia / (volatilidade * raiz(duracao))`, e o multiplicador e 95% do valor
  justo, limitado a 20x. Alvo colado no preco (menos de 0,001%) ou longe demais (multiplicador abaixo de 1,10x) e recusado.
  `GET /ordens/barreira` devolve esses parametros e a volatilidade atual de cada ativo.
- `duracaoSegundos`: 15, 30, 60 ou 300. `valor`: de 1,00 a 10.000,00.
- A rodada so e aceita se `saldo da carteira - rodadas ainda abertas - sessoes abertas do Flecha Certeira >= valor`
  (consulta sincrona ao wallet-service).
- Um agendador (1s) fecha as rodadas vencidas com o ultimo preco recebido e publica `ordem.executada`.

Padroes: **Strategy** (`estrategia/`: uma classe por tipo de previsao) e **Factory** (`fabrica/OrdemFactory`).

## Flecha Certeira (via gateway: `/api/jogos/flecha`)

Sessoes do jogo de arco e flecha do frontend. O servidor e a fonte da verdade das moedas do jogo: cobra a entrada e as
flechas, confere cada tiro, liquida as flechas de mercado e, ao encerrar, manda o resultado para a carteira. As tabelas
ficam em `flecha/regras/` (`Campo`, `Passaro`, `TipoFlecha`, `RegrasFlecha`) e espelham `src/regras.js` do frontend.

| Metodo | Caminho | Descricao |
|---|---|---|
| GET | `/jogos/flecha/regras` | Campos, passaros, flechas, limites, `niveisFlecha` (`[10, 20, 30]`), `valorMinimoFlecha` (`1`) e `taxaDevolucaoFlechas` (`0.002`). **Publico** (nao precisa de `X-Usuario-Id`) |
| GET | `/jogos/flecha/aljava` | Flechas guardadas de sessoes anteriores (comuns por campo; em `mercado`, so as de mercado guardadas antes do valor escolhido) |
| POST | `/jogos/flecha/sessoes` | Abre uma sessao. Corpo: `{"campo":"bosque","flechas":{"wood":10},"mercado":[{"flecha":"btc","valor":10,"quantidade":3},{"flecha":"eth","valor":25.5,"quantidade":1}]}` → **201** (`mercado` e opcional; `flechas` continua obrigatorio, pode ser `{}`) |
| GET | `/jogos/flecha/sessoes/atual` | Sessao ABERTA do jogador (**200**) ou nenhuma (**204**) |
| POST | `/jogos/flecha/sessoes/{id}/tiros` | Um tiro: `{"flecha":"wood","acertos":["red","big"]}` (passaros atravessados por esta flecha, na ordem) → `{"ganhosTiro":n,"sessao":{...}}` |
| POST | `/jogos/flecha/sessoes/{id}/flechas` | Compra mais flechas: `{"flecha":"btc","quantidade":2,"valor":20}` (`valor` obrigatorio nas de mercado, ignorado na madeira) |
| POST | `/jogos/flecha/sessoes/{id}/encerrar` | Encerra e publica o resultado (idempotente: se ja encerrada, so devolve) |

A sessao volta como `{ id, campo, status (ABERTA|ENCERRADA), custo, ganhos, resultado (= ganhos + devolucao -
custo), flechas (restantes por tipo), mercado, compradas, disparadas, acertos, devolucaoFlechas, criadaEm,
encerradaEm }`. `mercado` sao os lotes de flechas de mercado restantes, por flecha e depois por valor:
`[{"flecha":"btc","valor":10.00,"quantidade":2}, ...]`. `devolucaoFlechas` e o que voltou das flechas de mercado
nao usadas ao encerrar: `{"quantidade":3,"valorBruto":30.00,"taxa":0.06,"valorDevolvido":29.94}` (zeros antes de
encerrar ou sem sobra).

Tabelas:

| Campo (`slug`) | Entrada | Flecha base | Mult | Pesos red/big/yellow/blue/gold | Passaros de mercado |
|---|---|---|---|---|---|
| `vilarejo` | 5 | 1 | 2 | 5 / 3 / 1 / 0 / 0,3 | `btc` |
| `bosque` | 15 | 3 | 4 | 4 / 2 / 3 / 1 / 0,5 | `btc`, `eth` |
| `pico` | 40 | 8 | 10 | 3 / 1 / 3 / 3 / 0,8 | `btc`, `eth`, `ouro`, `v100` |
| `reserva-real` | 100 | 20 | 25 | 2 / 1 / 3 / 4 / 1,2 | `btc`, `eth`, `ouro`, `v100` |

Passaros: `red`, `big`, `yellow`, `blue` e `gold` valem 0 moedas (o tiro so conta o acerto). `btc`, `eth` e `ouro`
sao os passaros de mercado: ficam fora do sorteio e voam conforme a cotacao do ativo (BTC, ETH e ouro PAXG), so nos
campos que tem o ativo.
Flechas: `wood` (madeira) e gratis em todo campo. `btc`, `eth`, `ouro` e `v100` sao as flechas de mercado: o jogador
escolhe o valor de cada uma (nivel 1 = 10, 2 = 20, 3 = 30 moedas, ou qualquer valor a partir de 1 moeda com ate 2
casas, sem teto alem do saldo) e elas so sao vendidas nos campos que tem o ativo delas. As antigas `iron`, `gold` e `diamond` foram
removidas: pedido com quantidade > 0 delas da **400**, e as que estavam guardadas sao devolvidas a carteira
(preco base do campo x 2/4/8) num `ordem.executada` `FLECHA_REEMBOLSO` idempotente, pelo agendador.

Regras:

- Custo da sessao = entrada do campo + flechas novas (madeira: `fatorPreco` 0, gratis; flecha de mercado: valor x
  quantidade de cada lote). Flechas de mercado mandadas em `flechas` (clientes antigos, sem valor) valem como lotes
  de 10 moedas.
- Valor do passaro no campo = `round(moedas x mult)`; moedas de um acerto =
  `round(valor do passaro x fatorMoedas da flecha) x posicao no combo` (1o passaro da flecha x1, 2o x2, ...),
  com arredondamento meio-para-cima, igual ao `Math.round` do frontend. Como todo passaro vale 0, os tiros nao
  rendem moedas: so as flechas de mercado alteram o saldo (ver abaixo).
- Abrir: campo conhecido, quantidades >= 0, de 1 a 40 flechas na aljava (madeira + lotes), valor de cada lote de
  mercado >= 1 moeda com ate 2 casas (**422**). As flechas guardadas do campo entram sem custo; as de mercado
  guardadas antes desta regra (preco fixo antigo) entram como lotes de 100 moedas. Da para abrir sem comprar nada se
  houver ao menos uma guardada. Ao encerrar ou expirar, a madeira que sobra volta para a guarda do campo; as flechas
  de mercado que sobram **nao** sao guardadas: voltam em moedas, `soma(valor x quantidade) x (1 - taxa)` com 2 casas
  (meio-para-cima), somadas ao resultado da sessao (`flecha.taxa-devolucao`, padrao 0.002 = 0,1% em cada ponta da
  conversao real -> dolar -> real). Uma sessao ABERTA por jogador (**409**; garantido
  tambem por um UNIQUE no banco). O custo precisa caber no **saldo disponivel** = saldo da carteira
  (`GET /carteira` no wallet-service) - ordens abertas - sessoes abertas, senao **422**
  `Saldo insuficiente: disponivel X`. As ordens de `/ordens` tambem passaram a descontar as sessoes abertas.
- Tiro: sessao de outro jogador **404**, ja encerrada **409**; flecha de mercado (registrada pelo estudo, nao
  por `/tiros`), sem flecha daquele tipo, mais de 5 passaros ou passaro que nao aparece no campo **422**; menos de **150 ms** desde o tiro anterior **429**.
- Compra: 1 a 20 flechas por vez, aljava com no maximo 40 restantes, `valor` valido nas de mercado, saldo
  disponivel para o custo extra (**422**).
- Encerrar publica em `tigrinho.eventos`/`ordem.executada` o mesmo JSON que a carteira ja consome:
  `{ ordemId: id da sessao, usuarioId, simbolo: slug do campo, tipo: "FLECHA_CERTEIRA", valor: custo,
  resultado: GANHOU|PERDEU|EMPATOU, valorLiquido: ganhos + devolucao - custo, executadaEm }` (+ `ganhos` e `acertos`, que a
  carteira ignora). A carteira e idempotente por `ordemId`.
- Sessoes ABERTAS sem atividade ha mais de `flecha.expira-apos` sao encerradas por um agendador (a cada 60 s).
- Duas alteracoes simultaneas na mesma sessao (`@Version`) → **409**, o cliente tenta de novo.

### Flechas de mercado e estudo (via gateway: `/api/jogos/flecha/estudo`)

Cada flecha de mercado disparada e uma previsao do jogador sobre a direcao do preco do ativo (`ALTA` ou `QUEDA`)
nos proximos 5 s. Ela vira uma operacao do estudo sobre previsao de precos (sabedoria das massas), aberta na hora e
fechada 5 s depois; toda operacao nova e do tipo `BINARIA`.

| Metodo | Caminho | Descricao |
|---|---|---|
| POST | `/jogos/flecha/estudo/operacoes` | Registra a previsao e gasta uma flecha do ativo e do valor pedido da sessao aberta. Corpo: `{"sessaoId":"...","direcao":"ALTA","simbolo":"ETHUSDT","valor":20,"precoAgora":...,"precoPrevisto":...}` (`simbolo`: `BTCUSDT`, `ETHUSDT`, `PAXGUSDT` ou `1HZ100V`; padrao `BTCUSDT`. `valor`: lote da aljava, **422** se nao houver flecha do ativo com esse valor; sem ele, gasta a de menor valor) → **201** |
| GET | `/jogos/flecha/estudo/operacoes/{id}` | Uma operacao do jogador, com `moedasPagas` e a sessao atualizada |
| GET | `/jogos/flecha/estudo/meus-acertos` | Placar do jogador por ativo e total (`sessaoId` opcional filtra uma sessao) |
| GET | `/jogos/flecha/estudo/resumo` | Resumo do estudo: taxa de acerto e resultado por modo e por ativo |

Valor da flecha de mercado (R$ 1 = 1 moeda, `RegrasFlecha.MOEDAS_POR_REAL`):

- O valor escolhido pelo jogador e a aposta em moedas (paga na compra) e tambem o tamanho da operacao do estudo em
  reais (`nocionalBrl`): uma flecha de 20 moedas abre uma operacao de R$ 20.
- Quando a operacao fecha: direcao certa paga `valor + resultadoLiquidoBrl` (o resultado real da operacao, depois
  das taxas; na Deriv, o lucro do contrato), com 2 casas (meio-para-cima) e nunca menos que 0 (ex.: flecha de 10 com
  +R$ 0,30 paga 10,30; se as taxas comerem o lucro, -R$ 0,06 paga 9,94). Direcao errada paga 0 (perde a aposta).
  Empate (preco parado) devolve o valor.
- Se a operacao nao aconteceu (recusada ou com falha), o servidor devolve exatamente o valor.
- Com a sessao ainda aberta o pagamento entra nos ganhos dela; com a sessao ja encerrada vai direto para a
  carteira num `ordem.executada` proprio da operacao. Operacoes antigas guardam o pagamento da regra anterior.
- O placar de acertos (`acertouDirecao`, `meus-acertos`) continua contando so a direcao.
- No minimo 1 s entre flechas de mercado do mesmo jogador.

Modos do estudo (`ESTUDO_MODO`):

- `SIMULADO` (padrao): registra a previsao e calcula o resultado com o preco real, sem enviar ordens a nenhuma
  corretora.
- `REAL`: as operacoes do estudo no ativo configurado (`estudo.simbolo`, `BTCUSDT`) viram ordens reais na margem
  isolada da Binance, com a chave de API e o **dinheiro real do dono do jogo**: cada operacao tem o valor da flecha
  em reais (`ESTUDO_MARGEM_BRL` x `ESTUDO_ALAVANCAGEM` ficou so como referencia do resumo). So liga com `ESTUDO_MODO=REAL` e
  `BINANCE_API_KEY`/`BINANCE_API_SECRET` no ambiente (sem a chave, segue `SIMULADO`). Os outros ativos sao sempre
  simulados com o preco real.
- Travas do modo `REAL`: `ESTUDO_MAX_OPERACOES_DIA` (padrao 300) operacoes por dia, `ESTUDO_MAX_ABERTAS` (padrao 5)
  abertas ao mesmo tempo e `ESTUDO_PERDA_MAXIMA_DIA_BRL` (padrao R$ 50) de perda maxima no dia.

**Limitacao conhecida (anti-trapaca):** e o cliente que informa quais passaros cada flecha acertou. O servidor so
valida a **plausibilidade** (tem a flecha, combo ate 5, passaro existe no campo, ritmo minimo entre tiros); um
cliente adulterado ainda pode declarar acertos que nao aconteceram, dentro desses limites. A re-simulacao completa
dos tiros no servidor (posicoes, sementes do sorteio, fisica) fica para antes de trocar o sandbox pelo Pix real.

Configuracao:

| Propriedade | Variavel | Padrao | O que faz |
|---|---|---|---|
| `flecha.expira-apos` | `FLECHA_EXPIRA_APOS` | `10m` | Inatividade que encerra a sessao automaticamente |
| `flecha.intervalo-minimo-tiro` | — | `150ms` | Menor intervalo entre tiros (senao 429) |
| `flecha.taxa-devolucao` | `FLECHA_TAXA_DEVOLUCAO` | `0.002` | Fracao descontada das flechas de mercado que sobram e voltam em moedas ao encerrar (0,1% em cada ponta real/dolar) |
| `flecha.agendador.habilitado` | `FLECHA_AGENDADOR_HABILITADO` | `true` | Liga o encerramento automatico (desligado nos testes) |
| `flecha.agendador.intervalo-ms` | — | `60000` | Intervalo entre verificacoes |

## Rodando

```bash
mvn verify            # testes + relatorio Jacoco (target/site/jacoco); falha abaixo de 80% de linhas ou 75% de ramos
```

Pra subir com as dependencias (Postgres, RabbitMQ, demais servicos) use o `docker-compose.yml` do repo `api-gateway`.

Variaveis: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_HOST`, `MARKET_DATA_URL`, `WALLET_URL`.
Estudo: `ESTUDO_MODO` (`SIMULADO` ou `REAL`), `ESTUDO_MARGEM_BRL`, `ESTUDO_ALAVANCAGEM`, `ESTUDO_CAMBIO_RESERVA`,
`ESTUDO_MAX_OPERACOES_DIA`, `ESTUDO_MAX_ABERTAS`, `ESTUDO_PERDA_MAXIMA_DIA_BRL`, `BINANCE_URL`, `BINANCE_API_KEY`,
`BINANCE_API_SECRET` (a chave vem so do ambiente, nunca do repositorio).

## Repositorios do projeto

- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [api-gateway](https://github.com/tigrinho-trader/api-gateway)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
