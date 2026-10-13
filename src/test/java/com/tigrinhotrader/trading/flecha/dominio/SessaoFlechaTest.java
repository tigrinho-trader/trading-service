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
        // igual ao orderCost do frontend: 10 madeira (gratis) + 1 bitcoin (100) no vilarejo = 100, mais a entrada 5
        Aljava pedido = Aljava.de(Map.of(TipoFlecha.WOOD, 10, TipoFlecha.BTC, 1));
        assertThat(pedido.total()).isEqualTo(11);
        assertThat(pedido.custo(Campo.VILAREJO)).isEqualByComparingTo("100");
        assertThat(SessaoFlecha.custoAbertura(Campo.VILAREJO, pedido)).isEqualByComparingTo("105");
        // so madeira: so a entrada
        assertThat(SessaoFlecha.custoAbertura(Campo.PICO, Aljava.de(Map.of(TipoFlecha.WOOD, 40))))
                .isEqualByComparingTo("40");

        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO, pedido, agora);
        assertThat(s.getCusto()).isEqualByComparingTo("105").hasScaleOf(2);
        assertThat(s.getGanhos()).isEqualByComparingTo("0");
        assertThat(s.isAberta()).isTrue();
        assertThat(s.getStatus()).isEqualTo(StatusSessao.ABERTA);
        assertThat(s.getCampo()).isEqualTo(Campo.VILAREJO);
        assertThat(s.getUsuarioId()).isEqualTo("u1");
        assertThat(s.getCriadaEm()).isEqualTo(agora);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora);
        assertThat(s.getUltimoTiroEm()).isNull();
        assertThat(s.getRestantes().comoMapa()).containsExactly(Map.entry("wood", 10), Map.entry("btc", 1),
                Map.entry("eth", 0), Map.entry("ouro", 0));
        assertThat(s.getDisparadas().total()).isZero();
    }

    @Test
    void tiroContaAcertosMasPassaroNaoPagaMoedas() {
        SessaoFlecha s = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.WOOD, 2));
        Instant depois = agora.plusSeconds(1);

        // combo de 2 passaros (antes valia 25 x 15 + 2 x 75): hoje 0
        assertThat(s.registrarTiro(TipoFlecha.WOOD, List.of(Passaro.GOLD, Passaro.BTC), depois)).isZero();
        assertThat(s.registrarTiro(TipoFlecha.WOOD, List.of(), depois.plusSeconds(1))).isZero();

        assertThat(s.getGanhos()).isEqualByComparingTo("0");
        assertThat(s.getCusto()).isEqualByComparingTo("100");
        assertThat(s.getAcertos()).isEqualTo(2);
        assertThat(s.getRestantes().get(TipoFlecha.WOOD)).isZero();
        assertThat(s.getDisparadas().get(TipoFlecha.WOOD)).isEqualTo(2);
        assertThat(s.getCompradas().get(TipoFlecha.WOOD)).isEqualTo(2);
        assertThat(s.getUltimoTiroEm()).isEqualTo(depois.plusSeconds(1));
        assertThatThrownBy(() -> s.registrarTiro(TipoFlecha.WOOD, List.of(), depois))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compraSomaAoCustoEAsFlechas() {
        SessaoFlecha s = sessao(Campo.BOSQUE, Map.of(TipoFlecha.WOOD, 1));
        // so a entrada: a madeira e gratis
        assertThat(s.getCusto()).isEqualByComparingTo("15");

        assertThat(s.comprar(TipoFlecha.WOOD, 3, agora.plusSeconds(1))).isEqualByComparingTo("0");
        assertThat(s.comprar(TipoFlecha.ETH, 2, agora.plusSeconds(5))).isEqualByComparingTo("200");

        assertThat(s.getCusto()).isEqualByComparingTo("215");
        assertThat(s.getCompradas().get(TipoFlecha.WOOD)).isEqualTo(4);
        assertThat(s.getCompradas().get(TipoFlecha.ETH)).isEqualTo(2);
        assertThat(s.getRestantes().get(TipoFlecha.ETH)).isEqualTo(2);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora.plusSeconds(5));
    }

    @Test
    void resultadoFinal() {
        // so treino com madeira: acertar passaros nao paga, perde a entrada
        SessaoFlecha perdeu = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 1));
        perdeu.registrarTiro(TipoFlecha.WOOD, List.of(Passaro.RED, Passaro.GOLD), agora);
        assertThat(perdeu.resultado()).isEqualByComparingTo("-5");
        assertThat(perdeu.resultadoFinal()).isEqualTo("PERDEU");

        // so a aposta de mercado rende: custo 5 + 100, acertou a direcao paga 200
        SessaoFlecha ganhou = sessao(Campo.VILAREJO, Map.of(TipoFlecha.BTC, 1));
        ganhou.receberApostaDeMercado(200, true);
        assertThat(ganhou.resultado()).isEqualByComparingTo("95");
        assertThat(ganhou.resultadoFinal()).isEqualTo("GANHOU");

        // reserva real: 100 + 2 x 100 = 300; uma acertou (200) e outra ficou com o preco parado (100)
        SessaoFlecha empatou = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.BTC, 2));
        empatou.receberApostaDeMercado(200, true);
        empatou.receberApostaDeMercado(100, false);
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
        Aljava original = Aljava.de(Map.of(TipoFlecha.WOOD, 3));
        Aljava copia = original.copia();
        copia.somar(TipoFlecha.WOOD, -1);
        copia.somar(TipoFlecha.BTC, 4);
        copia.somar(TipoFlecha.ETH, 3);
        copia.somar(TipoFlecha.OURO, 1);

        assertThat(original.get(TipoFlecha.WOOD)).isEqualTo(3);
        assertThat(copia.comoMapa()).containsExactly(Map.entry("wood", 2), Map.entry("btc", 4), Map.entry("eth", 3),
                Map.entry("ouro", 1));
        assertThat(copia.total()).isEqualTo(10);
        assertThat(copia.copia().get(TipoFlecha.BTC)).isEqualTo(4);
        assertThat(copia.copia().get(TipoFlecha.ETH)).isEqualTo(3);
        assertThat(copia.copia().get(TipoFlecha.OURO)).isEqualTo(1);
        // madeira gratis, flechas de mercado 100 em qualquer campo
        assertThat(copia.custo(Campo.RESERVA_REAL)).isEqualByComparingTo("800");
        assertThat(copia.custo(Campo.VILAREJO)).isEqualByComparingTo("800");
        assertThat(Aljava.vazia().custo(Campo.RESERVA_REAL)).isEqualByComparingTo("0");
    }

    @Test
    void aljavaSeparaComunsDeMercadoESoma() {
        Aljava a = Aljava.de(Map.of(TipoFlecha.WOOD, 2, TipoFlecha.BTC, 3, TipoFlecha.OURO, 1));

        assertThat(a.comuns().comoMapa()).containsExactly(Map.entry("wood", 2), Map.entry("btc", 0),
                Map.entry("eth", 0), Map.entry("ouro", 0));
        assertThat(a.deMercado().total()).isEqualTo(4);
        assertThat(a.deMercado().get(TipoFlecha.WOOD)).isZero();
        assertThat(Aljava.soma(a, Aljava.de(Map.of(TipoFlecha.WOOD, 1))).get(TipoFlecha.WOOD)).isEqualTo(3);
        assertThat(a.get(TipoFlecha.WOOD)).isEqualTo(2);

        Aljava havia = a.esvaziar();
        assertThat(havia.total()).isEqualTo(6);
        assertThat(a.total()).isZero();
    }

    @Test
    void flechasAntigasNaoContamNemAparecemMasValemOQueCustaram() {
        // 1 ferro, 2 ouro, 3 diamante no bosque (preco base 3): 3 x (2 + 2 x 4 + 3 x 8) = 102
        Aljava antigas = Aljava.antigas(1, 2, 3);
        assertThat(antigas.antigas()).isEqualTo(6);
        assertThat(antigas.total()).isZero();
        assertThat(antigas.comoMapa()).containsOnlyKeys("wood", "btc", "eth", "ouro");
        assertThat(antigas.valorAntigas(Campo.BOSQUE)).isEqualByComparingTo("102");
        assertThat(antigas.valorAntigas(Campo.RESERVA_REAL)).isEqualByComparingTo("680");
        assertThat(antigas.custo(Campo.BOSQUE)).isEqualByComparingTo("0");

        // ficam com as comuns (sao do campo), somam e copiam; nao vao para as de mercado
        Aljava mistura = Aljava.soma(Aljava.de(Map.of(TipoFlecha.WOOD, 1, TipoFlecha.BTC, 1)), antigas);
        assertThat(mistura.antigas()).isEqualTo(6);
        assertThat(mistura.copia().antigas()).isEqualTo(6);
        assertThat(mistura.comuns().antigas()).isEqualTo(6);
        assertThat(mistura.deMercado().antigas()).isZero();

        // esvaziar deixa as antigas (esperam o reembolso); esvaziarTudo leva tudo
        Aljava jogaveis = mistura.esvaziar();
        assertThat(jogaveis.total()).isEqualTo(2);
        assertThat(jogaveis.antigas()).isZero();
        assertThat(mistura.antigas()).isEqualTo(6);
        Aljava tudo = mistura.esvaziarTudo();
        assertThat(tudo.antigas()).isEqualTo(6);
        assertThat(mistura.antigas()).isZero();
    }

    @Test
    void sessaoDeAntesComFlechasAntigasDevolveElasAoEncerrar() {
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.PICO, Aljava.de(Map.of(TipoFlecha.WOOD, 1)),
                Aljava.antigas(2, 0, 1), agora);
        assertThat(s.getRestantes().total()).isEqualTo(1);
        assertThat(s.getRestantes().comoMapa()).containsOnlyKeys("wood", "btc", "eth", "ouro");

        Aljava sobra = s.encerrar(agora);

        assertThat(sobra.antigas()).isEqualTo(3);
        assertThat(sobra.get(TipoFlecha.WOOD)).isEqualTo(1);
        assertThat(s.getRestantes().antigas()).isZero();
    }

    @Test
    void guardadasEntramNaAljavaSemCustoNemContarComoCompradas() {
        Aljava guardadas = Aljava.de(Map.of(TipoFlecha.WOOD, 3, TipoFlecha.ETH, 2));
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.BOSQUE, Aljava.de(Map.of(TipoFlecha.WOOD, 1)),
                guardadas, agora);

        // so a entrada 15: a madeira e gratis
        assertThat(s.getCusto()).isEqualByComparingTo("15");
        assertThat(s.getCompradas().total()).isEqualTo(1);
        assertThat(s.getRestantes().get(TipoFlecha.WOOD)).isEqualTo(4);
        assertThat(s.getRestantes().get(TipoFlecha.ETH)).isEqualTo(2);
        assertThat(s.getRestantes().total()).isEqualTo(6);

        // sem comprar nada: so a entrada
        SessaoFlecha soGuardadas = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.PICO, Aljava.vazia(), guardadas,
                agora);
        assertThat(soGuardadas.getCusto()).isEqualByComparingTo("40");
    }

    @Test
    void flechaDeMercadoEGastaPeloEstudoEAApostaSomaAosGanhos() {
        SessaoFlecha s = sessao(Campo.VILAREJO, Map.of(TipoFlecha.BTC, 1, TipoFlecha.WOOD, 1));
        // 5 + 0 (madeira) + 100
        assertThat(s.getCusto()).isEqualByComparingTo("105");

        s.usarFlechaDeMercado(TipoFlecha.BTC, agora.plusSeconds(3));

        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isZero();
        assertThat(s.getDisparadas().get(TipoFlecha.BTC)).isEqualTo(1);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora.plusSeconds(3));
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.BTC, agora))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.WOOD, agora))
                .isInstanceOf(IllegalStateException.class);

        s.receberApostaDeMercado(200, true);
        s.receberApostaDeMercado(100, false);
        s.receberApostaDeMercado(0, false);
        assertThat(s.getGanhos()).isEqualByComparingTo("300");
        assertThat(s.getAcertos()).isEqualTo(1);

        s.encerrar(agora);
        assertThatThrownBy(() -> s.receberApostaDeMercado(100, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.BTC, agora))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encerrarTiraAsFlechasQueSobraram() {
        SessaoFlecha s = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 3, TipoFlecha.ETH, 2));
        s.registrarTiro(TipoFlecha.WOOD, List.of(), agora);

        Aljava sobra = s.encerrar(agora.plusSeconds(1));

        assertThat(sobra.get(TipoFlecha.WOOD)).isEqualTo(2);
        assertThat(sobra.get(TipoFlecha.ETH)).isEqualTo(2);
        assertThat(s.getRestantes().total()).isZero();
        assertThat(s.getCompradas().total()).isEqualTo(5);
        assertThat(s.getDisparadas().total()).isEqualTo(1);
    }

    @Test
    void aljavaGuardadaGuardaERetira() {
        AljavaGuardada g = new AljavaGuardada("u1", "bosque", agora);
        assertThat(g.getUsuarioId()).isEqualTo("u1");
        assertThat(g.getEscopo()).isEqualTo("bosque");
        assertThat(g.getId()).isNotNull();
        assertThat(g.getFlechas().total()).isZero();

        g.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 2)), agora);
        g.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 1, TipoFlecha.BTC, 1)), agora);
        g.guardar(Aljava.antigas(1, 0, 0), agora);
        assertThat(g.getFlechas().get(TipoFlecha.WOOD)).isEqualTo(3);

        // as antigas ficam na guarda ate o reembolso
        Aljava retiradas = g.retirar(agora);
        assertThat(retiradas.total()).isEqualTo(4);
        assertThat(retiradas.antigas()).isZero();
        assertThat(g.getFlechas().total()).isZero();
        assertThat(g.getFlechas().antigas()).isEqualTo(1);

        // a referencia do reembolso e a mesma enquanto a aljava nao muda (retentativa idempotente)
        assertThat(g.referenciaReembolso()).isEqualTo(g.referenciaReembolso())
                .isNotEqualTo(new AljavaGuardada("u1", "bosque", agora).referenciaReembolso());
        g.zerarAntigas(agora.plusSeconds(1));
        assertThat(g.getFlechas().antigas()).isZero();
        assertThat(AljavaGuardada.MERCADO).isEqualTo("mercado");
    }
}
