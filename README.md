# trading-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Motor de ordens/rodadas do jogo. Recebe pedido de entrada ("aposta") vindo do frontend via API Gateway (REST sincrono), calcula o resultado usando o preco mais recente publicado pelo `market-data-service`, e publica o evento `ordem.executada` na fila para os demais servicos consumirem. Concentra os padroes Strategy (tipos de aposta/ordem) e Factory (criacao de rodada/ordem).

## Como interage com os outros servicos

- Consome (assincrono, via fila): eventos de preco publicados pelo `market-data-service`.
- Publica (assincrono): evento `ordem.executada`, consumido por `wallet-service` e `notification-service`.
- Exposto (sincrono, via API Gateway): endpoint de criacao de ordem e consulta de status.

## Repositorios do projeto

- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [api-gateway](https://github.com/tigrinho-trader/api-gateway)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
