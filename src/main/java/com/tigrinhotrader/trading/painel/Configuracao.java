package com.tigrinhotrader.trading.painel;

import com.tigrinhotrader.trading.estudo.EstudoProperties;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
import com.tigrinhotrader.trading.flecha.servico.FlechaProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * As regras em vigor que o painel do super-admin configura. O que nao foi configurado no painel vale o do ambiente
 * (.env / application.yml).
 *
 * @param taxaPoupanca fracao do pagamento de uma flecha de mercado vencedora que vai para a carteira de poupanca
 *     (0.05 = 5%: uma aposta de 10 que pagaria 19 paga 18,05 e 0,95 e poupado)
 * @param taxaDevolucao fracao descontada das flechas de mercado que sobram ao sair do campo
 * @param entradaLivre todos os campos com entrada 0 (senao, a tabela de cada campo)
 * @param niveis valores das flechas de mercado Nivel 1, 2 e 3
 * @param valorMinimo menor valor de uma flecha de mercado
 * @param maxOperacoesDia operacoes reais por dia, somando todos os perfis
 * @param maxAbertas operacoes reais abertas ao mesmo tempo
 * @param perdaMaximaDiaBrl perda real por dia (todos os perfis somados) que para as flechas de mercado
 */
public record Configuracao(BigDecimal taxaPoupanca, BigDecimal taxaDevolucao, boolean entradaLivre,
                           List<Integer> niveis, BigDecimal valorMinimo, int maxOperacoesDia, int maxAbertas,
                           BigDecimal perdaMaximaDiaBrl) {

    public Configuracao {
        niveis = List.copyOf(niveis);
    }

    /** As regras do ambiente (sem nada do painel); propriedades nulas usam os padroes do codigo. */
    public static Configuracao doAmbiente(FlechaProperties flecha, EstudoProperties estudo, boolean entradaLivre) {
        FlechaProperties f = flecha == null ? new FlechaProperties(null, null, null) : flecha;
        return new Configuracao(BigDecimal.ZERO, f.taxaDevolucao(), entradaLivre, RegrasFlecha.NIVEIS_FLECHA,
                RegrasFlecha.VALOR_MINIMO_FLECHA, estudo == null ? 300 : estudo.maxOperacoesDia(),
                estudo == null ? 5 : estudo.maxAbertas(),
                estudo == null ? new BigDecimal("50") : estudo.perdaMaximaDiaBrl());
    }
}
