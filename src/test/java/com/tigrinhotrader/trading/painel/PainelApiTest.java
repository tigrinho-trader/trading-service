package com.tigrinhotrader.trading.painel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Painel do super-admin no trading-service: so com o cabecalho do gateway; regras mudam na hora. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:painel;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PainelApiTest {

    private static final String ADMIN = PainelController.CABECALHO;

    @Autowired
    MockMvc mvc;
    @Autowired
    ConfiguracaoService configuracao;

    @AfterEach
    void voltarAoAmbiente() throws Exception {
        mvc.perform(put("/painel/config").header(ADMIN, "super-admin-oscar")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    }

    @Test
    void semOCabecalhoDoGatewayRecusa() throws Exception {
        mvc.perform(get("/painel/config")).andExpect(status().isForbidden());
        mvc.perform(put("/painel/config").contentType(MediaType.APPLICATION_JSON).content("{\"taxaPoupanca\":0.5}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/painel/resultados")).andExpect(status().isForbidden());
        assertThat(configuracao.atual().taxaPoupanca()).isEqualByComparingTo("0");
    }

    @Test
    void semNadaNoPainelValemAsRegrasDoAmbiente() throws Exception {
        mvc.perform(get("/painel/config").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atual.taxaPoupanca").value(0))
                .andExpect(jsonPath("$.atual.taxaDevolucao").value(0.002))      // a dos testes
                .andExpect(jsonPath("$.atual.entradaLivre").value(false))
                .andExpect(jsonPath("$.atual.niveis", contains(10, 20, 30)))
                .andExpect(jsonPath("$.ambiente.perdaMaximaDiaBrl").exists());
    }

    @Test
    void mudarPeloPainelValeNaHoraNasRegrasDoJogo() throws Exception {
        mvc.perform(put("/painel/config").header(ADMIN, "super-admin-oscar").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taxaPoupanca":0.05,"taxaDevolucao":0.01,"entradaLivre":true,"niveis":[5,15,25],
                                 "valorMinimo":2,"maxOperacoesDia":40,"maxAbertas":2,"perdaMaximaDiaBrl":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atual.taxaPoupanca").value(0.05))
                .andExpect(jsonPath("$.atual.maxOperacoesDia").value(40))
                .andExpect(jsonPath("$.ambiente.taxaPoupanca").value(0));

        assertThat(Campo.entradaLivre()).isTrue();
        mvc.perform(get("/jogos/flecha/regras"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.niveisFlecha", contains(5, 15, 25)))
                .andExpect(jsonPath("$.valorMinimoFlecha").value(2))
                .andExpect(jsonPath("$.taxaDevolucaoFlechas").value(0.01))
                .andExpect(jsonPath("$.campos[0].entrada").value(0));

        // campo vazio volta ao do ambiente
        mvc.perform(put("/painel/config").header(ADMIN, "super-admin-oscar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxaPoupanca\":0.05}"))
                .andExpect(jsonPath("$.atual.niveis", contains(10, 20, 30)))
                .andExpect(jsonPath("$.atual.entradaLivre").value(false));
        assertThat(Campo.entradaLivre()).isFalse();
    }

    @Test
    void valorInvalidoE422ENaoMudaNada() throws Exception {
        for (String corpo : new String[] {"{\"taxaPoupanca\":0.6}", "{\"taxaDevolucao\":-0.01}",
                "{\"niveis\":[10,10,30]}", "{\"niveis\":[10,20]}", "{\"valorMinimo\":11}",
                "{\"perdaMaximaDiaBrl\":0}", "{\"maxAbertas\":0}"}) {
            mvc.perform(put("/painel/config").header(ADMIN, "super-admin-oscar")
                            .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(configuracao.atual()).isEqualTo(configuracao.doAmbiente());
    }

    @Test
    void resultadosPorPerfilComecamVazios() throws Exception {
        mvc.perform(get("/painel/resultados").param("dias", "7").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfis", hasSize(0)))
                .andExpect(jsonPath("$.total.operacoes").value(0))
                .andExpect(jsonPath("$.total.lucro").value(0));
    }

    @Autowired
    com.tigrinhotrader.trading.estudo.OperacaoEstudoRepository operacoes;
    @Autowired
    com.tigrinhotrader.trading.config.AjusteEsquema ajuste;

    @Test
    void perdaDaDerivGravadaComOPayoutECorrigidaEOPainelMostraOResultadoNegativo() throws Exception {
        // como o fechamento gravava antes: usava o payout (3,31) como recebido numa opcao perdida
        var o = com.tigrinhotrader.trading.estudo.OperacaoEstudo.nova("perfil-deriv", java.util.UUID.randomUUID(),
                "1HZ100V", com.tigrinhotrader.trading.estudo.Direcao.ALTA,
                com.tigrinhotrader.trading.estudo.TipoPrevisao.BINARIA, com.tigrinhotrader.trading.estudo.ModoEstudo.REAL,
                java.math.BigDecimal.TEN, null, null, Instant.now());
        var zero = java.math.BigDecimal.ZERO;
        var abertura = new com.tigrinhotrader.trading.estudo.Execucao("deriv-1", zero,
                new java.math.BigDecimal("1348.71"), new java.math.BigDecimal("-1.74"), zero, zero, zero);
        var fechamento = new com.tigrinhotrader.trading.estudo.Execucao("deriv-fechado-1", zero,
                new java.math.BigDecimal("1347.71"), new java.math.BigDecimal("3.31"), zero, zero, zero);
        o.registrarAbertura(abertura, new java.math.BigDecimal("5.60"), Instant.now());
        o.fechar(fechamento, com.tigrinhotrader.trading.estudo.ResultadoOperacao.calcular(
                com.tigrinhotrader.trading.estudo.Direcao.ALTA, abertura, fechamento, new java.math.BigDecimal("5.60"),
                java.math.BigDecimal.TEN), Instant.now());
        o.liquidar();
        operacoes.save(o);
        assertThat(operacoes.findById(o.getId()).orElseThrow().getResultadoLiquidoBrl()).isPositive();   // o defeito

        assertThat(ajuste.corrigirPerdasDaDeriv()).isEqualTo(1);
        assertThat(ajuste.corrigirPerdasDaDeriv()).isZero();                                             // so uma vez
        assertThat(operacoes.findById(o.getId()).orElseThrow().getResultadoLiquidoBrl()).isEqualByComparingTo("-9.744");

        mvc.perform(get("/painel/resultados").header(ADMIN, "super-admin-oscar"))
                .andExpect(jsonPath("$.perfis[0].usuarioId").value("perfil-deriv"))
                .andExpect(jsonPath("$.perfis[0].erros").value(1))
                .andExpect(jsonPath("$.perfis[0].lucro").value(-10))
                .andExpect(jsonPath("$.perfis[0].resultadoRealBrl").value(-9.74));
        operacoes.delete(o);
    }

    @Test
    void periodoContaOsDiasNoHorarioDeSaoPaulo() {
        Instant agora = Instant.parse("2026-11-16T02:30:00Z");           // 23:30 de 15/11 em Sao Paulo
        assertThat(PainelController.desde(1, agora)).isEqualTo(Instant.parse("2026-11-15T03:00:00Z"));
        assertThat(PainelController.desde(7, agora)).isEqualTo(Instant.parse("2026-11-09T03:00:00Z"));
        assertThat(PainelController.desde(0, agora)).isEqualTo(Instant.EPOCH);
        assertThat(PainelController.desde(null, agora)).isEqualTo(Instant.EPOCH);
    }
}
