package com.tigrinhotrader.trading.flecha.regras;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.LoteFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.web.RegrasFlechaResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Entrada livre por tempo limitado: a cobranca e a mesma (entrada + flechas), so a entrada vale 0. */
class EntradaLivreTest {

    @AfterEach
    void voltarParaATabela() {
        Campo.definirEntradaLivre(false); // os outros testes da cobranca usam a entrada de tabela
    }

    @Test
    void comAEntradaLivreTodoCampoCobraZeroEATabelaFicaGuardada() {
        Campo.definirEntradaLivre(true);

        assertThat(Campo.values()).extracting(Campo::entrada).allSatisfy(e -> assertThat(e).isEqualByComparingTo("0"));
        assertThat(Campo.values()).extracting(Campo::entradaTabela)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.5"), new BigDecimal("1.5"), new BigDecimal("4"), new BigDecimal("10"));

        Campo.definirEntradaLivre(false);
        assertThat(Campo.PICO.entrada()).isEqualByComparingTo("4"); // desligando, volta a cobrar a tabela
    }

    @Test
    void custoDaRodadaComEntradaLivreESoAsFlechas() {
        Campo.definirEntradaLivre(true);

        // so madeira (gratis): a rodada sai de graca
        assertThat(SessaoFlecha.custoAbertura(Campo.RESERVA_REAL, Aljava.de(Map.of(TipoFlecha.WOOD, 40)), List.of()))
                .isEqualByComparingTo("0");
        // flechas de mercado continuam custando o valor escolhido
        assertThat(SessaoFlecha.custoAbertura(Campo.PICO, Aljava.de(Map.of(TipoFlecha.WOOD, 10)),
                List.of(new LoteFlecha(TipoFlecha.V100, new BigDecimal("20"), 2)))).isEqualByComparingTo("40");

        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO,
                Aljava.de(Map.of(TipoFlecha.WOOD, 10)), Instant.parse("2026-11-12T12:00:00Z"));
        assertThat(s.getCusto()).isEqualByComparingTo("0");
        assertThat(s.resultado()).isEqualByComparingTo("0"); // sem apostas, a rodada empata
    }

    @Test
    void regrasPublicasMostramAEntradaQueEstaSendoCobrada() {
        Campo.definirEntradaLivre(true);
        assertThat(RegrasFlechaResponse.atuais(new BigDecimal("0.002")).campos())
                .allSatisfy(c -> assertThat(c.entrada()).isEqualByComparingTo("0"));

        Campo.definirEntradaLivre(false);
        assertThat(RegrasFlechaResponse.atuais(new BigDecimal("0.002")).campos().get(0).entrada())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void naSubidaDoServicoAEntradaLivreVemDaConfiguracao() {
        new EntradaLivreConfig(true);
        assertThat(Campo.entradaLivre()).isTrue();

        new EntradaLivreConfig(false);
        assertThat(Campo.entradaLivre()).isFalse();
    }
}
