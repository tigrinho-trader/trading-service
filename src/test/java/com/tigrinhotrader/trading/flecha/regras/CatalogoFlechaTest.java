package com.tigrinhotrader.trading.flecha.regras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** As tabelas e contas precisam bater com src/regras.js do frontend (e regras.test.js). */
class CatalogoFlechaTest {

    @Test
    void exemplosDoContratoIguaisAoFrontend() {
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.WOOD, 1)).isEqualTo(4);
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.GOLD, 1)).isEqualTo(22);
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.DIAMOND, 1)).isEqualTo(48);
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.IRON, 2)).isEqualTo(20);
        assertThat(Campo.RESERVA_REAL.valorPassaro(Passaro.GOLD)).isEqualTo(375);
        assertThat(Campo.VILAREJO.valorPassaro(Passaro.RED)).isEqualTo(4);
    }

    @Test
    void custoDaFlechaEFatorVezesPrecoBaseDoCampo() {
        assertThat(Campo.VILAREJO.custoFlecha(TipoFlecha.WOOD)).isEqualByComparingTo("1");
        assertThat(Campo.VILAREJO.custoFlecha(TipoFlecha.DIAMOND)).isEqualByComparingTo("8");
        assertThat(Campo.RESERVA_REAL.custoFlecha(TipoFlecha.GOLD)).isEqualByComparingTo("80");
        assertThat(Campo.BOSQUE.custoFlecha(TipoFlecha.IRON)).isEqualByComparingTo("6");
        assertThat(Campo.PICO.custoFlecha(TipoFlecha.WOOD)).isEqualByComparingTo("8");
    }

    @Test
    void flechaDeMercadoCustaCemMoedasEmTodoCampo() {
        // a aposta binaria de R$ 10 do estudo, a 10 moedas = R$ 1
        assertThat(RegrasFlecha.PRECO_FLECHA_MERCADO).isEqualTo(100);
        assertThat(RegrasFlecha.PAGAMENTO_ACERTO_MERCADO).isEqualTo(200);
        for (Campo c : Campo.values()) {
            assertThat(c.custoFlecha(TipoFlecha.BTC)).isEqualByComparingTo("100");
            assertThat(c.custoFlecha(TipoFlecha.ETH)).isEqualByComparingTo("100");
            assertThat(c.custoFlecha(TipoFlecha.OURO)).isEqualByComparingTo("100");
        }
        assertThat(TipoFlecha.values()).filteredOn(TipoFlecha::deMercado)
                .containsExactly(TipoFlecha.BTC, TipoFlecha.ETH, TipoFlecha.OURO);
    }

    @Test
    void arredondaMeioParaCimaComoOMathRound() {
        // big na Reserva Real vale 25: 25 x 2,5 = 62,5 -> 63; 25 x 5,5 = 137,5 -> 138
        assertThat(Campo.RESERVA_REAL.moedasAcerto(Passaro.BIG, TipoFlecha.IRON, 1)).isEqualTo(63);
        assertThat(Campo.RESERVA_REAL.moedasAcerto(Passaro.BIG, TipoFlecha.GOLD, 1)).isEqualTo(138);
        assertThat(Campo.RESERVA_REAL.moedasAcerto(Passaro.BIG, TipoFlecha.GOLD, 3)).isEqualTo(414);
        assertThat(Campo.PICO.moedasAcerto(Passaro.BLUE, TipoFlecha.DIAMOND, 5)).isEqualTo(50 * 12 * 5);
    }

    @Test
    void comboComecaEmUm() {
        assertThatThrownBy(() -> Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.WOOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void camposMaisCarosPagamMaisPeloMesmoPassaro() {
        List<Integer> valores = Arrays.stream(Campo.values()).map(c -> c.valorPassaro(Passaro.RED)).toList();
        assertThat(valores).containsExactly(4, 8, 20, 50).isSorted();
    }

    @Test
    void flechasMelhoresRendemMaisPorMoedaGasta() {
        double anterior = 0;
        for (TipoFlecha t : TipoFlecha.values()) {
            double rendimento = t.fatorMoedas().doubleValue() / t.fatorPreco();
            if (t.passaroAlvo().isPresent()) {
                // flechas de mercado: todas rendem o mesmo, mais que a de diamante
                assertThat(rendimento).isEqualTo(2.0).isGreaterThan(1.5);
                continue;
            }
            assertThat(rendimento).isGreaterThan(anterior);
            anterior = rendimento;
        }
    }

    @Test
    void tabelaDosCampos() {
        assertThat(Campo.values()).extracting(Campo::slug)
                .containsExactly("vilarejo", "bosque", "pico", "reserva-real");
        assertThat(Campo.values()).extracting(Campo::entrada)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("5"), new BigDecimal("15"), new BigDecimal("40"), new BigDecimal("100"));
        assertThat(Campo.values()).extracting(Campo::flechaBase).containsExactly(1, 3, 8, 20);
        assertThat(Campo.values()).extracting(Campo::mult).containsExactly(2, 4, 10, 25);
        assertThat(Campo.PICO.nome()).isEqualTo("Pico das Águias");
        assertThat(Campo.VILAREJO.pool().get(Passaro.GOLD)).isEqualByComparingTo("0.3");
        assertThat(Campo.RESERVA_REAL.pool().get(Passaro.BLUE)).isEqualByComparingTo("4");
        assertThat(Campo.BOSQUE.pool().get(Passaro.GOLD)).isEqualByComparingTo("0.5");
        assertThat(Campo.PICO.pool().get(Passaro.GOLD)).isEqualByComparingTo("0.8");
        assertThatThrownBy(() -> Campo.VILAREJO.pool().put(Passaro.BLUE, BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void passaroComPesoZeroNaoApareceNoCampo() {
        assertThat(Campo.VILAREJO.temNoPool(Passaro.BLUE)).isFalse();
        assertThat(Campo.VILAREJO.temNoPool(Passaro.GOLD)).isTrue();
        assertThat(Campo.BOSQUE.temNoPool(Passaro.BLUE)).isTrue();
        // o bitcoin nasce em todo campo, fora do sorteio
        for (Campo c : Campo.values()) {
            assertThat(c.temNoPool(Passaro.BTC)).isTrue();
            assertThat(c.pool()).doesNotContainKey(Passaro.BTC);
        }
        assertThat(Passaro.BTC.emTodoCampo()).isTrue();
        assertThat(Passaro.GOLD.emTodoCampo()).isFalse();
        // mesmos exemplos do frontend (regras.js): 3 x mult
        assertThat(Campo.VILAREJO.valorPassaro(Passaro.BTC)).isEqualTo(6);
        assertThat(Campo.RESERVA_REAL.valorPassaro(Passaro.BTC)).isEqualTo(75);
        // flecha bitcoin: dobro das moedas do passaro bitcoin, e so acerta ele
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.BTC, TipoFlecha.BTC, 1)).isEqualTo(12);
        assertThat(Campo.RESERVA_REAL.moedasAcerto(Passaro.BTC, TipoFlecha.BTC, 1)).isEqualTo(150);
        assertThat(TipoFlecha.BTC.acerta(Passaro.BTC)).isTrue();
        assertThat(TipoFlecha.BTC.acerta(Passaro.GOLD)).isFalse();
        assertThat(TipoFlecha.WOOD.acerta(Passaro.BTC)).isTrue();
        assertThat(TipoFlecha.DIAMOND.acerta(Passaro.RED)).isTrue();
    }

    @Test
    void passarosEFlechasDeEthereumEOuroSaoComoOsDoBitcoin() {
        for (Campo c : Campo.values()) {
            assertThat(c.temNoPool(Passaro.ETH)).isTrue();
            assertThat(c.temNoPool(Passaro.OURO)).isTrue();
            assertThat(c.pool()).doesNotContainKeys(Passaro.ETH, Passaro.OURO);
        }
        assertThat(Passaro.ETH.emTodoCampo()).isTrue();
        assertThat(Passaro.OURO.emTodoCampo()).isTrue();
        // vilarejo: 3 x 2 = 6, a flecha do ativo dobra: 12
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.ETH, TipoFlecha.ETH, 1)).isEqualTo(12);
        assertThat(Campo.VILAREJO.moedasAcerto(Passaro.OURO, TipoFlecha.OURO, 1)).isEqualTo(12);
        // cada flecha de mercado so acerta o passaro do seu ativo
        assertThat(TipoFlecha.ETH.acerta(Passaro.ETH)).isTrue();
        assertThat(TipoFlecha.ETH.acerta(Passaro.BTC)).isFalse();
        assertThat(TipoFlecha.ETH.acerta(Passaro.OURO)).isFalse();
        assertThat(TipoFlecha.OURO.acerta(Passaro.OURO)).isTrue();
        assertThat(TipoFlecha.OURO.acerta(Passaro.GOLD)).isFalse();
        assertThat(TipoFlecha.BTC.acerta(Passaro.ETH)).isFalse();
        assertThat(TipoFlecha.WOOD.acerta(Passaro.ETH)).isTrue();
        assertThat(TipoFlecha.GOLD.acerta(Passaro.OURO)).isTrue();
        assertThat(TipoFlecha.BTC.passaroAlvo()).contains(Passaro.BTC);
        assertThat(TipoFlecha.ETH.passaroAlvo()).contains(Passaro.ETH);
        assertThat(TipoFlecha.OURO.passaroAlvo()).contains(Passaro.OURO);
        assertThat(TipoFlecha.IRON.passaroAlvo()).isEmpty();
        // nome diferente da flecha de ouro comum, para as mensagens nao confundirem
        assertThat(TipoFlecha.OURO.nome()).isEqualTo("Ouro PAXG");
        assertThat(TipoFlecha.GOLD.nome()).isEqualTo("Ouro");
        assertThat(TipoFlecha.porId("ouro")).contains(TipoFlecha.OURO);
        assertThat(Passaro.porId("eth")).contains(Passaro.ETH);
    }

    @Test
    void tabelasDePassarosEFlechas() {
        assertThat(Passaro.values()).extracting(Passaro::id)
                .containsExactly("red", "big", "yellow", "blue", "gold", "btc", "eth", "ouro");
        assertThat(Passaro.values()).extracting(Passaro::moedas).containsExactly(2, 1, 4, 5, 15, 3, 3, 3);
        assertThat(TipoFlecha.values()).extracting(TipoFlecha::id)
                .containsExactly("wood", "iron", "gold", "diamond", "btc", "eth", "ouro");
        assertThat(TipoFlecha.values()).extracting(TipoFlecha::fatorPreco).containsExactly(1, 2, 4, 8, 1, 1, 1);
        assertThat(TipoFlecha.ETH.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.OURO.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.ETH.nome()).isEqualTo("Ethereum");
        assertThat(TipoFlecha.BTC.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.BTC.nome()).isEqualTo("Bitcoin");
        assertThat(TipoFlecha.IRON.fatorMoedas()).isEqualByComparingTo("2.5");
        assertThat(TipoFlecha.DIAMOND.nome()).isEqualTo("Diamante");
    }

    @Test
    void buscaPorIdESlug() {
        assertThat(Campo.porSlug("reserva-real")).contains(Campo.RESERVA_REAL);
        assertThat(Campo.porSlug("lua")).isEmpty();
        assertThat(Passaro.porId("gold")).contains(Passaro.GOLD);
        assertThat(Passaro.porId("pinguim")).isEmpty();
        assertThat(TipoFlecha.porId("diamond")).contains(TipoFlecha.DIAMOND);
        assertThat(TipoFlecha.porId("laser")).isEmpty();
    }

    @Test
    void limites() {
        assertThat(RegrasFlecha.MAX_FLECHAS).isEqualTo(40);
        assertThat(RegrasFlecha.MAX_COMBO).isEqualTo(5);
        assertThat(RegrasFlecha.MAX_COMPRA).isEqualTo(20);
    }
}
