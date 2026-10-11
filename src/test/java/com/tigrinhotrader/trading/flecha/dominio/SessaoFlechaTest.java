package com.tigrinhotrader.trading.flecha.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessaoFlechaTest {

    private final Instant agora = Instant.parse("2026-10-01T12:00:00Z");

    private SessaoFlecha sessao(Campo campo, Map<TipoFlecha, Integer> flechas) {
        return new SessaoFlecha(UUID.randomUUID(), "u1", campo, Aljava.de(flechas), agora);
    }

    @Test
    void custoDeAberturaEEntradaMaisFlechas() {
        // igual ao orderCost do frontend: 10 madeira + 5 ferro + 2 ouro + 1 diamante no vilarejo = 36
        Aljava pedido = Aljava.de(Map.of(TipoFlecha.WOOD, 10, TipoFlecha.IRON, 5, TipoFlecha.GOLD, 2,
                TipoFlecha.DIAMOND, 1));
        assertThat(pedido.total()).isEqualTo(18);
        assertThat(pedido.custo(Campo.VILAREJO)).isEqualByComparingTo("36");
        assertThat(SessaoFlecha.custoAbertura(Campo.VILAREJO, pedido)).isEqualByComparingTo("41");

        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO, pedido, agora);
        assertThat(s.getCusto()).isEqualByComparingTo("41").hasScaleOf(2);
        assertThat(s.getGanhos()).isEqualByComparingTo("0");
        assertThat(s.isAberta()).isTrue();
        assertThat(s.getStatus()).isEqualTo(StatusSessao.ABERTA);
        assertThat(s.getCampo()).isEqualTo(Campo.VILAREJO);
        assertThat(s.getUsuarioId()).isEqualTo("u1");
        assertThat(s.getCriadaEm()).isEqualTo(agora);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora);
        assertThat(s.getUltimoTiroEm()).isNull();
        assertThat(s.getRestantes().comoMapa()).containsExactly(Map.entry("wood", 10), Map.entry("iron", 5),
                Map.entry("gold", 2), Map.entry("diamond", 1));
        assertThat(s.getDisparadas().total()).isZero();
    }

    @Test
    void tiroSomaMoedasComComboEGastaUmaFlecha() {
        SessaoFlecha s = sessao(Campo.VILAREJO, Map.of(TipoFlecha.IRON, 2));
        Instant depois = agora.plusSeconds(1);

        // red (10) + red no 2o do combo (20)
        assertThat(s.registrarTiro(TipoFlecha.IRON, List.of(Passaro.RED, Passaro.RED), depois)).isEqualTo(30);
        assertThat(s.registrarTiro(TipoFlecha.IRON, List.of(), depois.plusSeconds(1))).isZero();

        assertThat(s.getGanhos()).isEqualByComparingTo("30");
        assertThat(s.getAcertos()).isEqualTo(2);
        assertThat(s.getRestantes().get(TipoFlecha.IRON)).isZero();
        assertThat(s.getDisparadas().get(TipoFlecha.IRON)).isEqualTo(2);
        assertThat(s.getCompradas().get(TipoFlecha.IRON)).isEqualTo(2);
        assertThat(s.getUltimoTiroEm()).isEqualTo(depois.plusSeconds(1));
        assertThatThrownBy(() -> s.registrarTiro(TipoFlecha.IRON, List.of(), depois))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compraSomaAoCustoEAsFlechas() {
        SessaoFlecha s = sessao(Campo.BOSQUE, Map.of(TipoFlecha.WOOD, 1));
        assertThat(s.getCusto()).isEqualByComparingTo("18");

        assertThat(s.comprar(TipoFlecha.DIAMOND, 2, agora.plusSeconds(5))).isEqualByComparingTo("48");

        assertThat(s.getCusto()).isEqualByComparingTo("66");
        assertThat(s.getCompradas().get(TipoFlecha.DIAMOND)).isEqualTo(2);
        assertThat(s.getRestantes().get(TipoFlecha.DIAMOND)).isEqualTo(2);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora.plusSeconds(5));
    }

    @Test
    void resultadoFinal() {
        SessaoFlecha perdeu = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 1));
        assertThat(perdeu.resultado()).isEqualByComparingTo("-6");
        assertThat(perdeu.resultadoFinal()).isEqualTo("PERDEU");

        SessaoFlecha ganhou = sessao(Campo.VILAREJO, Map.of(TipoFlecha.GOLD, 1));
        ganhou.registrarTiro(TipoFlecha.GOLD, List.of(Passaro.RED), agora);
        assertThat(ganhou.resultado()).isEqualByComparingTo("13");
        assertThat(ganhou.resultadoFinal()).isEqualTo("GANHOU");

        // custo 5 + 2 = 7; tiro: red (4) + big no 2o do combo (2 x 2 = 4) = 8; mais 1 flecha (1) -> custo 8
        SessaoFlecha empatou = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 2));
        empatou.registrarTiro(TipoFlecha.WOOD, List.of(Passaro.RED, Passaro.BIG), agora);
        empatou.comprar(TipoFlecha.WOOD, 1, agora);
        assertThat(empatou.resultado()).isEqualByComparingTo("0");
        assertThat(empatou.resultadoFinal()).isEqualTo("EMPATOU");
    }

    @Test
    void encerrarFechaUmaVezSo() {
        SessaoFlecha s = sessao(Campo.PICO, Map.of(TipoFlecha.WOOD, 3));
        s.encerrar(agora.plusSeconds(60));

        assertThat(s.isAberta()).isFalse();
        assertThat(s.getStatus()).isEqualTo(StatusSessao.ENCERRADA);
        assertThat(s.getEncerradaEm()).isEqualTo(agora.plusSeconds(60));
        assertThatThrownBy(() -> s.encerrar(agora)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.comprar(TipoFlecha.WOOD, 1, agora)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.registrarTiro(TipoFlecha.WOOD, List.of(), agora))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aljavaCopiaEIndependente() {
        Aljava original = Aljava.de(Map.of(TipoFlecha.GOLD, 3));
        Aljava copia = original.copia();
        copia.somar(TipoFlecha.GOLD, -1);
        copia.somar(TipoFlecha.WOOD, 2);

        assertThat(original.get(TipoFlecha.GOLD)).isEqualTo(3);
        assertThat(copia.comoMapa()).containsExactly(Map.entry("wood", 2), Map.entry("iron", 0),
                Map.entry("gold", 2), Map.entry("diamond", 0));
        assertThat(Aljava.vazia().custo(Campo.RESERVA_REAL)).isEqualByComparingTo("0");
    }
}
