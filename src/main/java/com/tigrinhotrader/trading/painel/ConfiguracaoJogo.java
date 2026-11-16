package com.tigrinhotrader.trading.painel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * O que o super-admin configurou no painel (uma linha so, id 1). Campo nulo = vale o do ambiente.
 * Os niveis ficam em tres colunas; a lista so faz sentido com os tres.
 */
@Entity
@Table(name = "configuracao_jogo")
public class ConfiguracaoJogo {

    static final int ID = 1;

    @Id
    private Integer id = ID;

    @Column(precision = 6, scale = 4)
    BigDecimal taxaPoupanca;
    @Column(precision = 6, scale = 4)
    BigDecimal taxaDevolucao;
    Boolean entradaLivre;
    Integer nivel1;
    Integer nivel2;
    Integer nivel3;
    @Column(precision = 12, scale = 2)
    BigDecimal valorMinimo;
    Integer maxOperacoesDia;
    Integer maxAbertas;
    @Column(precision = 12, scale = 2)
    BigDecimal perdaMaximaDiaBrl;
    Instant atualizadaEm;

    protected ConfiguracaoJogo() {
        // JPA
    }

    static ConfiguracaoJogo nova() {
        return new ConfiguracaoJogo();
    }
}
