package com.tigrinhotrader.trading.estrategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class EstrategiasTest {

    private final RegistroEstrategias registro =
            new RegistroEstrategias(List.of(new EstrategiaAlta(), new EstrategiaBaixa(), new EstrategiaLateral()));

    @ParameterizedTest(name = "{0}: {1} -> {2} = {3}")
    @CsvSource({
            "ALTA,    100, 101,     GANHOU",
            "ALTA,    100, 99,      PERDEU",
            "ALTA,    100, 100.00,  EMPATOU",
            "BAIXA,   100, 99,      GANHOU",
            "BAIXA,   100, 101,     PERDEU",
            "BAIXA,   100, 100,     EMPATOU",
            "LATERAL, 100, 100,     GANHOU",
            "LATERAL, 100, 100.05,  GANHOU",
            "LATERAL, 100, 99.95,   GANHOU",
            "LATERAL, 100, 100.06,  PERDEU",
            "LATERAL, 100, 99.90,   PERDEU"
    })
    void resolveCadaTipoDeAposta(TipoOrdem tipo, BigDecimal entrada, BigDecimal saida, StatusOrdem esperado) {
        assertThat(registro.para(tipo).resolver(entrada, saida)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} {1}: {2} -> {3} = {4}")
    @CsvSource({
            // FACIL: qualquer movimento vale, LATERAL tolera 0,10%
            "FACIL,   ALTA,    100, 100.001, GANHOU",
            "FACIL,   BAIXA,   100, 100,     EMPATOU",
            "FACIL,   LATERAL, 100, 100.10,  GANHOU",
            "FACIL,   LATERAL, 100, 100.11,  PERDEU",
            // INSANO: ALTA/BAIXA precisam andar 0,02%, LATERAL so tolera 0,01%
            "INSANO,  ALTA,    100, 100.02,  GANHOU",
            "INSANO,  ALTA,    100, 100.019, PERDEU",
            "INSANO,  ALTA,    100, 100,     PERDEU",
            "INSANO,  BAIXA,   100, 99.98,   GANHOU",
            "INSANO,  BAIXA,   100, 99.99,   PERDEU",
            "INSANO,  LATERAL, 100, 100.01,  GANHOU",
            "INSANO,  LATERAL, 100, 99.98,   PERDEU",
            "DIFICIL, LATERAL, 100, 100.05,  GANHOU"
    })
    void resolveDeAcordoComOModo(ModoJogo modo, TipoOrdem tipo, BigDecimal entrada, BigDecimal saida,
                                 StatusOrdem esperado) {
        assertThat(registro.para(tipo).resolver(entrada, saida, modo)).isEqualTo(esperado);
    }

    @Test
    void modoMaisArriscadoPagaMais() {
        assertThat(registro.para(TipoOrdem.ALTA).multiplicador(ModoJogo.FACIL)).isEqualByComparingTo("1.50");
        assertThat(registro.para(TipoOrdem.BAIXA).multiplicador(ModoJogo.INSANO)).isEqualByComparingTo("4.00");
        assertThat(registro.para(TipoOrdem.LATERAL).multiplicador(ModoJogo.FACIL)).isEqualByComparingTo("1.80");
        assertThat(registro.para(TipoOrdem.LATERAL).multiplicador(ModoJogo.INSANO)).isEqualByComparingTo("6.00");
    }

    @Test
    void multiplicadoresPagamMaisQuemArriscaMais() {
        assertThat(registro.para(TipoOrdem.ALTA).multiplicador()).isEqualByComparingTo("1.90");
        assertThat(registro.para(TipoOrdem.BAIXA).multiplicador()).isEqualByComparingTo("1.90");
        assertThat(registro.para(TipoOrdem.LATERAL).multiplicador()).isEqualByComparingTo("2.50");
    }

    @Test
    void registroRecusaTipoSemEstrategia() {
        RegistroEstrategias incompleto = new RegistroEstrategias(List.of(new EstrategiaAlta()));
        assertThatThrownBy(() -> incompleto.para(TipoOrdem.BAIXA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registroRecusaEstrategiaDuplicada() {
        assertThatThrownBy(() -> new RegistroEstrategias(List.of(new EstrategiaAlta(), new EstrategiaAlta())))
                .isInstanceOf(IllegalStateException.class);
    }
}
