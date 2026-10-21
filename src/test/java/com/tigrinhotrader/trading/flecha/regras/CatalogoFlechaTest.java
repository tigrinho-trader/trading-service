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
    void passarosNaoPagamMoedasComNenhumaFlechaEmNenhumCampo() {
        // so as apostas das flechas de mercado rendem moedas
        for (Campo c : Campo.values()) {
            for (Passaro p : Passaro.values()) {
                assertThat(c.valorPassaro(p)).isZero();
                for (TipoFlecha t : TipoFlecha.values()) {
                    for (int combo = 1; combo <= RegrasFlecha.MAX_COMBO; combo++) {
                        assertThat(c.moedasAcerto(p, t, combo)).isZero();
                    }
                }
            }
        }
    }

    @Test
    void madeiraEGratisEmTodoCampo() {
        for (Campo c : Campo.values()) {
            assertThat(c.custoFlecha(TipoFlecha.WOOD)).isEqualByComparingTo("0");
        }
        assertThat(TipoFlecha.WOOD.fatorPreco()).isZero();
    }

    @Test
    void ferroOuroEDiamanteSairamDoJogo() {
        assertThat(TipoFlecha.values()).containsExactly(TipoFlecha.WOOD, TipoFlecha.BTC, TipoFlecha.ETH,
                TipoFlecha.OURO, TipoFlecha.V100);
        assertThat(TipoFlecha.porId("iron")).isEmpty();
        assertThat(TipoFlecha.porId("gold")).isEmpty();
        assertThat(TipoFlecha.porId("diamond")).isEmpty();
    }

    @Test
    void flechaDeMercadoCustaOValorEscolhidoPeloJogador() {
        // 1 real = 1 moeda: o valor da flecha e a aposta em moedas e a operacao em reais
        assertThat(RegrasFlecha.MOEDAS_POR_REAL).isEqualTo(1);
        assertThat(RegrasFlecha.NIVEIS_FLECHA).containsExactly(10, 20, 30);
        assertThat(RegrasFlecha.VALOR_MINIMO_FLECHA).isEqualByComparingTo("1");
        assertThat(RegrasFlecha.CASAS_VALOR_FLECHA).isEqualTo(2);
        // clientes antigos (sem valor) compram o nivel 1; as guardadas do preco fixo antigo valem 100
        assertThat(RegrasFlecha.VALOR_FLECHA_PADRAO).isEqualByComparingTo("10").hasScaleOf(2);
        assertThat(RegrasFlecha.VALOR_FLECHA_ANTIGA).isEqualByComparingTo("100").hasScaleOf(2);
        // nao ha preco do campo para elas
        for (Campo c : Campo.values()) {
            for (TipoFlecha t : TipoFlecha.values()) {
                if (t.deMercado()) {
                    assertThatThrownBy(() -> c.custoFlecha(t)).isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
        assertThat(TipoFlecha.values()).filteredOn(TipoFlecha::deMercado)
                .containsExactly(TipoFlecha.BTC, TipoFlecha.ETH, TipoFlecha.OURO, TipoFlecha.V100);
    }

    @Test
    void camposMaisCarosTemMaisAtivos() {
        assertThat(Campo.VILAREJO.passarosMercado()).containsExactly(Passaro.BTC);
        assertThat(Campo.BOSQUE.passarosMercado()).containsExactly(Passaro.BTC, Passaro.ETH);
        assertThat(Campo.PICO.passarosMercado()).containsExactly(Passaro.BTC, Passaro.ETH, Passaro.OURO,
                Passaro.V100);
        assertThat(Campo.RESERVA_REAL.passarosMercado()).containsExactly(Passaro.BTC, Passaro.ETH, Passaro.OURO,
                Passaro.V100);
        List<Integer> quantos = Arrays.stream(Campo.values()).map(c -> c.passarosMercado().size()).toList();
        assertThat(quantos).isSorted();
        assertThatThrownBy(() -> Campo.VILAREJO.passarosMercado().add(Passaro.ETH))
                .isInstanceOf(UnsupportedOperationException.class);

        // flecha de mercado so vale no campo com o ativo; as comuns valem em todos
        assertThat(Campo.VILAREJO.temFlecha(TipoFlecha.BTC)).isTrue();
        assertThat(Campo.VILAREJO.temFlecha(TipoFlecha.ETH)).isFalse();
        assertThat(Campo.VILAREJO.temFlecha(TipoFlecha.OURO)).isFalse();
        assertThat(Campo.BOSQUE.temFlecha(TipoFlecha.ETH)).isTrue();
        assertThat(Campo.BOSQUE.temFlecha(TipoFlecha.OURO)).isFalse();
        assertThat(Campo.PICO.temFlecha(TipoFlecha.OURO)).isTrue();
        assertThat(Campo.RESERVA_REAL.temFlecha(TipoFlecha.OURO)).isTrue();
        assertThat(Campo.BOSQUE.temFlecha(TipoFlecha.V100)).isFalse();
        assertThat(Campo.PICO.temFlecha(TipoFlecha.V100)).isTrue();
        assertThat(TipoFlecha.V100.passaroAlvo()).contains(Passaro.V100);
        for (Campo c : Campo.values()) {
            assertThat(c.temFlecha(TipoFlecha.WOOD)).isTrue();
        }
    }

    @Test
    void comboComecaEmUm() {
        assertThatThrownBy(() -> Campo.VILAREJO.moedasAcerto(Passaro.RED, TipoFlecha.WOOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tabelaDosCampos() {
        assertThat(Campo.values()).extracting(Campo::slug)
                .containsExactly("vilarejo", "bosque", "pico", "reserva-real");
        // a entrada continua cobrada
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
        assertThat(Passaro.BTC.deMercado()).isTrue();
        assertThat(Passaro.GOLD.deMercado()).isFalse();
        // flecha bitcoin so acerta o passaro bitcoin
        assertThat(TipoFlecha.BTC.acerta(Passaro.BTC)).isTrue();
        assertThat(TipoFlecha.BTC.acerta(Passaro.GOLD)).isFalse();
        assertThat(TipoFlecha.WOOD.acerta(Passaro.BTC)).isTrue();
        assertThat(TipoFlecha.WOOD.acerta(Passaro.RED)).isTrue();
    }

    @Test
    void passarosDeEthereumEOuroSoNosCamposComOAtivo() {
        assertThat(Campo.VILAREJO.temNoPool(Passaro.ETH)).isFalse();
        assertThat(Campo.VILAREJO.temNoPool(Passaro.OURO)).isFalse();
        assertThat(Campo.BOSQUE.temNoPool(Passaro.ETH)).isTrue();
        assertThat(Campo.BOSQUE.temNoPool(Passaro.OURO)).isFalse();
        assertThat(Campo.PICO.temNoPool(Passaro.OURO)).isTrue();
        assertThat(Campo.RESERVA_REAL.temNoPool(Passaro.ETH)).isTrue();
        for (Campo c : Campo.values()) {
            assertThat(c.pool()).doesNotContainKeys(Passaro.ETH, Passaro.OURO);
        }
        assertThat(Passaro.ETH.deMercado()).isTrue();
        assertThat(Passaro.OURO.deMercado()).isTrue();
        // cada flecha de mercado so acerta o passaro do seu ativo
        assertThat(TipoFlecha.ETH.acerta(Passaro.ETH)).isTrue();
        assertThat(TipoFlecha.ETH.acerta(Passaro.BTC)).isFalse();
        assertThat(TipoFlecha.ETH.acerta(Passaro.OURO)).isFalse();
        assertThat(TipoFlecha.OURO.acerta(Passaro.OURO)).isTrue();
        assertThat(TipoFlecha.OURO.acerta(Passaro.GOLD)).isFalse();
        assertThat(TipoFlecha.BTC.acerta(Passaro.ETH)).isFalse();
        assertThat(TipoFlecha.WOOD.acerta(Passaro.ETH)).isTrue();
        assertThat(TipoFlecha.BTC.passaroAlvo()).contains(Passaro.BTC);
        assertThat(TipoFlecha.ETH.passaroAlvo()).contains(Passaro.ETH);
        assertThat(TipoFlecha.OURO.passaroAlvo()).contains(Passaro.OURO);
        assertThat(TipoFlecha.WOOD.passaroAlvo()).isEmpty();
        assertThat(TipoFlecha.OURO.nome()).isEqualTo("Ouro PAXG");
        assertThat(TipoFlecha.porId("ouro")).contains(TipoFlecha.OURO);
        assertThat(Passaro.porId("eth")).contains(Passaro.ETH);
    }

    @Test
    void tabelasDePassarosEFlechas() {
        assertThat(Passaro.values()).extracting(Passaro::id)
                .containsExactly("red", "big", "yellow", "blue", "gold", "btc", "eth", "ouro", "v100");
        assertThat(Passaro.values()).extracting(Passaro::moedas).containsOnly(0);
        assertThat(TipoFlecha.values()).extracting(TipoFlecha::id)
                .containsExactly("wood", "btc", "eth", "ouro", "v100");
        assertThat(TipoFlecha.values()).extracting(TipoFlecha::fatorPreco).containsExactly(0, 1, 1, 1, 1);
        assertThat(TipoFlecha.ETH.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.OURO.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.ETH.nome()).isEqualTo("Ethereum");
        assertThat(TipoFlecha.BTC.fatorMoedas()).isEqualByComparingTo("2");
        assertThat(TipoFlecha.BTC.nome()).isEqualTo("Bitcoin");
        assertThat(TipoFlecha.WOOD.fatorMoedas()).isEqualByComparingTo("1");
        assertThat(TipoFlecha.WOOD.nome()).isEqualTo("Madeira");
    }

    @Test
    void buscaPorIdESlug() {
        assertThat(Campo.porSlug("reserva-real")).contains(Campo.RESERVA_REAL);
        assertThat(Campo.porSlug("lua")).isEmpty();
        assertThat(Passaro.porId("gold")).contains(Passaro.GOLD);
        assertThat(Passaro.porId("pinguim")).isEmpty();
        assertThat(TipoFlecha.porId("wood")).contains(TipoFlecha.WOOD);
        assertThat(TipoFlecha.porId("laser")).isEmpty();
    }

    @Test
    void limites() {
        assertThat(RegrasFlecha.MAX_FLECHAS).isEqualTo(40);
        assertThat(RegrasFlecha.MAX_COMBO).isEqualTo(5);
        assertThat(RegrasFlecha.MAX_COMPRA).isEqualTo(20);
    }
}
