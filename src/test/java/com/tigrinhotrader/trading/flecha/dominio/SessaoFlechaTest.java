package com.tigrinhotrader.trading.flecha.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SessaoFlechaTest {

    private final Instant agora = Instant.parse("2026-10-01T12:00:00Z");

    private SessaoFlecha sessao(Campo campo, Map<TipoFlecha, Integer> flechas) {
        return new SessaoFlecha(UUID.randomUUID(), "u1", campo, Aljava.de(flechas), agora);
    }

    @Test
    void custoDeAberturaEEntradaMaisFlechas() {
        // 10 madeira (gratis) + 1 bitcoin sem valor (cliente antigo: nivel 1, 10 moedas) no vilarejo, mais a entrada
        // 0,5
        Aljava pedido = Aljava.de(Map.of(TipoFlecha.WOOD, 10, TipoFlecha.BTC, 1));
        assertThat(pedido.total()).isEqualTo(11);
        // o custo da aljava so conta as comuns: as de mercado custam o valor de cada lote
        assertThat(pedido.custo(Campo.VILAREJO)).isEqualByComparingTo("0");
        assertThat(SessaoFlecha.lotesPadrao(pedido.deMercado()))
                .containsExactly(new LoteFlecha(TipoFlecha.BTC, new BigDecimal("10"), 1));
        assertThat(SessaoFlecha.custoAbertura(Campo.VILAREJO, pedido.comuns(),
                SessaoFlecha.lotesPadrao(pedido.deMercado()))).isEqualByComparingTo("10.5");
        // so madeira: so a entrada
        assertThat(SessaoFlecha.custoAbertura(Campo.PICO, Aljava.de(Map.of(TipoFlecha.WOOD, 40)), List.of()))
                .isEqualByComparingTo("4");

        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO, pedido, agora);
        assertThat(s.getCusto()).isEqualByComparingTo("10.50").hasScaleOf(2);
        assertThat(s.getGanhos()).isEqualByComparingTo("0");
        assertThat(s.isAberta()).isTrue();
        assertThat(s.getStatus()).isEqualTo(StatusSessao.ABERTA);
        assertThat(s.getCampo()).isEqualTo(Campo.VILAREJO);
        assertThat(s.getUsuarioId()).isEqualTo("u1");
        assertThat(s.getCriadaEm()).isEqualTo(agora);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora);
        assertThat(s.getUltimoTiroEm()).isNull();
        assertThat(s.getRestantes().comoMapa()).containsExactly(Map.entry("wood", 10), Map.entry("btc", 1),
                Map.entry("eth", 0), Map.entry("ouro", 0), Map.entry("v100", 0));
        assertThat(s.getLotesMercado()).containsExactly(new LoteFlecha(TipoFlecha.BTC, new BigDecimal("10.00"), 1));
        assertThat(s.getDisparadas().total()).isZero();
        assertThat(s.getDevolucaoQuantidade()).isZero();
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("0");
    }

    @Test
    void tiroContaAcertosMasPassaroNaoPagaMoedas() {
        SessaoFlecha s = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.WOOD, 2));
        Instant depois = agora.plusSeconds(1);

        // combo de 2 passaros (antes valia 25 x 15 + 2 x 75): hoje 0
        assertThat(s.registrarTiro(TipoFlecha.WOOD, List.of(Passaro.GOLD, Passaro.BTC), depois)).isZero();
        assertThat(s.registrarTiro(TipoFlecha.WOOD, List.of(), depois.plusSeconds(1))).isZero();

        assertThat(s.getGanhos()).isEqualByComparingTo("0");
        assertThat(s.getCusto()).isEqualByComparingTo("10");
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
        assertThat(s.getCusto()).isEqualByComparingTo("1.5");

        assertThat(s.comprar(TipoFlecha.WOOD, 3, agora.plusSeconds(1))).isEqualByComparingTo("0");
        // sem valor: nivel 1 (10 moedas cada); com valor: o lote daquele valor
        assertThat(s.comprar(TipoFlecha.ETH, 2, agora.plusSeconds(5))).isEqualByComparingTo("20");
        assertThat(s.comprar(TipoFlecha.ETH, 1, new BigDecimal("25.5"), agora.plusSeconds(6)))
                .isEqualByComparingTo("25.50");
        assertThat(s.comprar(TipoFlecha.ETH, 2, new BigDecimal("10"), agora.plusSeconds(7)))
                .isEqualByComparingTo("20");
        // o valor e ignorado na madeira
        assertThat(s.comprar(TipoFlecha.WOOD, 1, new BigDecimal("99"), agora.plusSeconds(7))).isEqualByComparingTo("0");

        assertThat(s.getCusto()).isEqualByComparingTo("67.00");
        assertThat(s.getCompradas().get(TipoFlecha.WOOD)).isEqualTo(5);
        assertThat(s.getCompradas().get(TipoFlecha.ETH)).isEqualTo(5);
        assertThat(s.getRestantes().get(TipoFlecha.ETH)).isEqualTo(5);
        assertThat(s.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("10"), 4),
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("25.50"), 1));
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora.plusSeconds(7));
    }

    @Test
    void resultadoFinal() {
        // so treino com madeira: acertar passaros nao paga, perde a entrada
        SessaoFlecha perdeu = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 1));
        perdeu.registrarTiro(TipoFlecha.WOOD, List.of(Passaro.RED, Passaro.GOLD), agora);
        assertThat(perdeu.resultado()).isEqualByComparingTo("-0.5");
        assertThat(perdeu.resultadoFinal()).isEqualTo("PERDEU");

        // so a aposta de mercado rende: custo 0,5 + 10, a operacao lucrou R$ 10,30 e pagou 20,30
        SessaoFlecha ganhou = sessao(Campo.VILAREJO, Map.of(TipoFlecha.BTC, 1));
        ganhou.receberApostaDeMercado(new BigDecimal("20.30"), true);
        assertThat(ganhou.resultado()).isEqualByComparingTo("9.80");
        assertThat(ganhou.resultadoFinal()).isEqualTo("GANHOU");

        // reserva real: 10 + 2 x 10 = 30; pagamentos de 20,31 e 9,69 devolvem o custo
        SessaoFlecha empatou = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.BTC, 2));
        empatou.receberApostaDeMercado(new BigDecimal("20.31"), true);
        empatou.receberApostaDeMercado(new BigDecimal("9.69"), false);
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
        copia.somar(TipoFlecha.V100, 2);
        assertThat(copia.comoMapa()).containsExactly(Map.entry("wood", 2), Map.entry("btc", 4), Map.entry("eth", 3),
                Map.entry("ouro", 1), Map.entry("v100", 2));
        assertThat(copia.total()).isEqualTo(12);
        assertThat(copia.copia().get(TipoFlecha.V100)).isEqualTo(2);
        assertThat(copia.copia().get(TipoFlecha.BTC)).isEqualTo(4);
        assertThat(copia.copia().get(TipoFlecha.ETH)).isEqualTo(3);
        assertThat(copia.copia().get(TipoFlecha.OURO)).isEqualTo(1);
        // madeira gratis; as de mercado nao entram no custo da aljava (custam o valor dos lotes)
        assertThat(copia.custo(Campo.RESERVA_REAL)).isEqualByComparingTo("0");
        assertThat(copia.custo(Campo.VILAREJO)).isEqualByComparingTo("0");
        assertThat(Aljava.vazia().custo(Campo.RESERVA_REAL)).isEqualByComparingTo("0");
    }

    @Test
    void aljavaSeparaComunsDeMercadoESoma() {
        Aljava a = Aljava.de(Map.of(TipoFlecha.WOOD, 2, TipoFlecha.BTC, 3, TipoFlecha.OURO, 1));

        assertThat(a.comuns().comoMapa()).containsExactly(Map.entry("wood", 2), Map.entry("btc", 0),
                Map.entry("eth", 0), Map.entry("ouro", 0), Map.entry("v100", 0));
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
        assertThat(antigas.comoMapa()).containsOnlyKeys("wood", "btc", "eth", "ouro", "v100");
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
        assertThat(s.getRestantes().comoMapa()).containsOnlyKeys("wood", "btc", "eth", "ouro", "v100");

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

        // so a entrada 1,5: a madeira e gratis
        assertThat(s.getCusto()).isEqualByComparingTo("1.5");
        assertThat(s.getCompradas().total()).isEqualTo(1);
        assertThat(s.getRestantes().get(TipoFlecha.WOOD)).isEqualTo(4);
        assertThat(s.getRestantes().get(TipoFlecha.ETH)).isEqualTo(2);
        assertThat(s.getRestantes().total()).isEqualTo(6);
        // as de mercado guardadas foram compradas pelo preco fixo antigo: lote de 100
        assertThat(s.getLotesMercado()).containsExactly(new LoteFlecha(TipoFlecha.ETH, new BigDecimal("100"), 2));

        // sem comprar nada: so a entrada
        SessaoFlecha soGuardadas = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.PICO, Aljava.vazia(), guardadas,
                agora);
        assertThat(soGuardadas.getCusto()).isEqualByComparingTo("4");
    }

    @Test
    void flechaDeMercadoEGastaPeloEstudoEAApostaSomaAosGanhos() {
        SessaoFlecha s = sessao(Campo.VILAREJO, Map.of(TipoFlecha.BTC, 1, TipoFlecha.WOOD, 1));
        // 0,5 + 0 (madeira) + 10
        assertThat(s.getCusto()).isEqualByComparingTo("10.5");

        assertThat(s.usarFlechaDeMercado(TipoFlecha.BTC, agora.plusSeconds(3))).isEqualByComparingTo("10")
                .hasScaleOf(2);

        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isZero();
        assertThat(s.getDisparadas().get(TipoFlecha.BTC)).isEqualTo(1);
        assertThat(s.getUltimaAtividadeEm()).isEqualTo(agora.plusSeconds(3));
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.BTC, agora))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.WOOD, agora))
                .isInstanceOf(IllegalStateException.class);

        // pagamentos com centavos (aposta + resultado liquido): os ganhos guardam 2 casas
        s.receberApostaDeMercado(new BigDecimal("10.31"), true);
        s.receberApostaDeMercado(new BigDecimal("9.40"), true);
        s.receberApostaDeMercado(new BigDecimal("0.00"), false);
        assertThat(s.getGanhos()).isEqualByComparingTo("19.71").hasScaleOf(2);
        assertThat(s.getAcertos()).isEqualTo(2);

        s.encerrar(agora);
        assertThatThrownBy(() -> s.receberApostaDeMercado(BigDecimal.TEN, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.BTC, agora))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encerrarTiraAsFlechasQueSobraramEDevolveAsDeMercado() {
        SessaoFlecha s = sessao(Campo.BOSQUE, Map.of(TipoFlecha.WOOD, 3, TipoFlecha.ETH, 3));
        s.registrarTiro(TipoFlecha.WOOD, List.of(), agora);

        Aljava sobra = s.encerrar(agora.plusSeconds(1), new BigDecimal("0.002"));

        // so as comuns voltam para a guarda
        assertThat(sobra.get(TipoFlecha.WOOD)).isEqualTo(2);
        assertThat(sobra.get(TipoFlecha.ETH)).isZero();
        assertThat(s.getRestantes().total()).isZero();
        assertThat(s.getLotesMercado()).isEmpty();
        assertThat(s.getCompradas().total()).isEqualTo(6);
        assertThat(s.getDisparadas().total()).isEqualTo(1);
        // 3 ethereum de 10: 30 - 0,2% = 29,94
        assertThat(s.getDevolucaoQuantidade()).isEqualTo(3);
        assertThat(s.getDevolucaoBruto()).isEqualByComparingTo("30.00").hasScaleOf(2);
        assertThat(s.getDevolucaoTaxa()).isEqualByComparingTo("0.06").hasScaleOf(2);
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("29.94");
        // a devolucao entra no resultado: 0 + 29,94 - (1,5 + 30)
        assertThat(s.resultado()).isEqualByComparingTo("-1.56");
        assertThat(s.getGanhos()).isEqualByComparingTo("0");
    }

    @Test
    void lotesPorTipoEValorComValorEscolhidoEAsGuardadasDoPrecoAntigo() {
        List<LoteFlecha> mercado = List.of(
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("20"), 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("25.5"), 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("10"), 2));
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.PICO, Aljava.de(Map.of(TipoFlecha.WOOD, 2)),
                mercado, Aljava.de(Map.of(TipoFlecha.BTC, 1)), agora);

        // entrada 4 + 20 + 25,50 + 2 x 10; a bitcoin guardada ja foi paga
        assertThat(s.getCusto()).isEqualByComparingTo("69.50");
        assertThat(s.getCompradas().get(TipoFlecha.BTC)).isEqualTo(3);
        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isEqualTo(4);
        assertThat(s.getRestantes().get(TipoFlecha.WOOD)).isEqualTo(2);
        // ordenados por tipo e depois por valor; a guardada e um lote de 100
        assertThat(s.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("10"), 2),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("25.50"), 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("100"), 1),
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("20"), 1));
        assertThat(s.getLotesMercado().get(1).total()).isEqualByComparingTo("25.50");
        assertThat(s.temFlechaDeMercado(TipoFlecha.BTC, new BigDecimal("25.5"))).isTrue();
        assertThat(s.temFlechaDeMercado(TipoFlecha.BTC, new BigDecimal("25.50"))).isTrue();
        assertThat(s.temFlechaDeMercado(TipoFlecha.BTC, new BigDecimal("30"))).isFalse();
        assertThat(s.temFlechaDeMercado(TipoFlecha.V100, new BigDecimal("10"))).isFalse();

        // gasta a do valor pedido; sem valor, a de menor valor
        assertThat(s.usarFlechaDeMercado(TipoFlecha.BTC, new BigDecimal("25.5"), agora)).isEqualByComparingTo("25.50");
        assertThat(s.usarFlechaDeMercado(TipoFlecha.BTC, null, agora)).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.BTC, new BigDecimal("25.50"), agora))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.usarFlechaDeMercado(TipoFlecha.V100, null, agora))
                .isInstanceOf(IllegalStateException.class);
        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isEqualTo(2);
        assertThat(s.getDisparadas().get(TipoFlecha.BTC)).isEqualTo(2);

        assertThat(s.comprar(TipoFlecha.BTC, 2, new BigDecimal("30"), agora)).isEqualByComparingTo("60");
        assertThat(s.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("10"), 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("30"), 2),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("100"), 1),
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("20"), 1));

        // sobram 10 + 2 x 30 + 100 + 20 = 190: menos 0,2% = 189,62
        Aljava sobra = s.encerrar(agora, new BigDecimal("0.002"));
        assertThat(sobra.get(TipoFlecha.WOOD)).isEqualTo(2);
        assertThat(sobra.deMercado().total()).isZero();
        assertThat(s.getDevolucaoQuantidade()).isEqualTo(5);
        assertThat(s.getDevolucaoBruto()).isEqualByComparingTo("190.00");
        assertThat(s.getDevolucaoTaxa()).isEqualByComparingTo("0.38");
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("189.62");
        // 0 de ganhos + 189,62 - (69,50 + 60)
        assertThat(s.resultado()).isEqualByComparingTo("60.12");
    }

    @Test
    void devolucaoArredondaEmCentavosHalfUpESemSobraEZero() {
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO, Aljava.vazia(),
                List.of(new LoteFlecha(TipoFlecha.BTC, new BigDecimal("25.5"), 1)), Aljava.vazia(), agora);
        // 25,50 x 0,998 = 25,449 -> 25,45
        s.encerrar(agora, new BigDecimal("0.002"));
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("25.45");
        assertThat(s.getDevolucaoTaxa()).isEqualByComparingTo("0.05");

        SessaoFlecha pequena = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO, Aljava.vazia(),
                List.of(new LoteFlecha(TipoFlecha.BTC, new BigDecimal("1.25"), 1)), Aljava.vazia(), agora);
        // 1,25 x 0,998 = 1,2475 -> 1,25: a taxa some no arredondamento
        pequena.encerrar(agora, new BigDecimal("0.002"));
        assertThat(pequena.getDevolucaoValor()).isEqualByComparingTo("1.25");
        assertThat(pequena.getDevolucaoTaxa()).isEqualByComparingTo("0");

        SessaoFlecha semSobra = sessao(Campo.VILAREJO, Map.of(TipoFlecha.WOOD, 1));
        semSobra.encerrar(agora, new BigDecimal("0.002"));
        assertThat(semSobra.getDevolucaoQuantidade()).isZero();
        assertThat(semSobra.getDevolucaoBruto()).isEqualByComparingTo("0").hasScaleOf(2);
        assertThat(semSobra.getDevolucaoTaxa()).isEqualByComparingTo("0");
        assertThat(semSobra.resultado()).isEqualByComparingTo("-0.5");
    }

    @Test
    void sessaoAbertaAntesDosLotesUsaOValorAntigo() {
        // linha de antes da coluna lotes_mercado (default '[]'), ou com texto estragado: os totais da aljava mandam;
        // tipo comum, desconhecido ou sem valor no texto e ignorado
        String ignorados = "[{\"flecha\":\"wood\",\"valor\":5,\"quantidade\":1},"
                + "{\"flecha\":\"laser\",\"valor\":5,\"quantidade\":1},{\"flecha\":\"btc\",\"quantidade\":1}]";
        for (String gravado : new String[] {"[]", "lixo", "", null, ignorados}) {
            SessaoFlecha s = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.BTC, 2, TipoFlecha.OURO, 1));
            ReflectionTestUtils.setField(s, "lotesMercado", gravado);

            assertThat(s.getLotesMercado()).as("gravado %s", gravado).containsExactly(
                    new LoteFlecha(TipoFlecha.BTC, new BigDecimal("100"), 2),
                    new LoteFlecha(TipoFlecha.OURO, new BigDecimal("100"), 1));
            assertThat(s.usarFlechaDeMercado(TipoFlecha.BTC, agora)).isEqualByComparingTo("100");
            s.encerrar(agora, BigDecimal.ZERO);
            assertThat(s.getDevolucaoBruto()).isEqualByComparingTo("200");
        }
        // com lote gravado, so o que falta vira lote antigo
        SessaoFlecha parcial = sessao(Campo.RESERVA_REAL, Map.of(TipoFlecha.BTC, 2));
        ReflectionTestUtils.setField(parcial, "lotesMercado", "[{\"flecha\":\"btc\",\"valor\":20,\"quantidade\":1}]");
        assertThat(parcial.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("20"), 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("100"), 1));
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
