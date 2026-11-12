package com.tigrinhotrader.trading.flecha.regras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Liga a entrada livre dos campos na subida do servico ({@code flecha.entrada-livre}, padrao true: em producao nao
 * precisa configurar nada). Com false, volta a cobrar a entrada de tabela de cada campo.
 */
@Component
public class EntradaLivreConfig {

    private static final Logger log = LoggerFactory.getLogger(EntradaLivreConfig.class);

    public EntradaLivreConfig(@Value("${flecha.entrada-livre:true}") boolean livre) {
        Campo.definirEntradaLivre(livre);
        log.info("Flecha Certeira: {}", livre ? "entrada livre por tempo limitado (todos os campos cobram 0)"
                : "entrada de tabela em cada campo");
    }
}
