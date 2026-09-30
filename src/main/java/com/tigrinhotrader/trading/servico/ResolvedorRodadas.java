package com.tigrinhotrader.trading.servico;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Relogio do jogo: a cada ciclo fecha as rodadas cujo tempo acabou. */
@Component
public class ResolvedorRodadas {

    private final OrdemService ordemService;

    public ResolvedorRodadas(OrdemService ordemService) {
        this.ordemService = ordemService;
    }

    @Scheduled(fixedDelayString = "${trading.resolucao-intervalo-ms:1000}")
    public void executar() {
        ordemService.resolverVencidas();
    }
}
