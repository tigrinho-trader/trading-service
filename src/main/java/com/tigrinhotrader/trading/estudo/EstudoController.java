package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.estudo.OperacaoEstudoService.MeusAcertos;
import com.tigrinhotrader.trading.estudo.OperacaoEstudoService.Placar;
import com.tigrinhotrader.trading.estudo.OperacaoEstudoService.Registro;
import com.tigrinhotrader.trading.flecha.web.SessaoResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estudo da sabedoria das massas: a operacao feita com cada flecha de mercado (que gasta a flecha da sessao aberta
 * e e liquidada como aposta de 100 moedas mais o resultado liquido da operacao) e o resumo do estudo.
 */
@RestController
@RequestMapping("/jogos/flecha/estudo")
public class EstudoController {

    /**
     * Previsao do jogador no instante do disparo: sobe ou desce. {@code tipo} e aceito e ignorado (a operacao e
     * sempre BINARIA). {@code simbolo}: BTCUSDT, ETHUSDT ou PAXGUSDT (sem ele, BTCUSDT).
     */
    public record NovaOperacao(@NotNull UUID sessaoId, @NotBlank String direcao, String tipo,
                               @Positive BigDecimal precoAgora, @Positive BigDecimal precoPrevisto, String simbolo) {
    }

    /**
     * O que o jogador ve: o ativo, a direcao, o modo e, quando fechar, quanto a operacao ganhou ou perdeu.
     * {@code moedasPagas}: o que a aposta de 100 moedas pagou, numero com 2 casas: 100 + 10 x resultadoLiquidoBrl
     * na fechada (ex.: 99.40, 100.31; nunca menos que 0), 100 na recusada ou com falha; nulo ate liquidar.
     * Operacoes antigas guardam o pagamento da regra anterior (200/100/0). {@code sessao}: a sessao da flecha, com
     * a aljava e os ganhos atualizados (nula se nao existe).
     */
    public record OperacaoResponse(UUID id, String simbolo, Direcao direcao, TipoPrevisao tipo, ModoEstudo modo,
                                   StatusOperacao status, String motivo, BigDecimal nocionalBrl,
                                   BigDecimal precoAgoraJogo, BigDecimal precoPrevistoJogo, BigDecimal precoEntrada,
                                   BigDecimal precoSaida, BigDecimal resultadoBrutoBrl, BigDecimal taxasBrl,
                                   BigDecimal resultadoLiquidoBrl, Boolean acertouDirecao,
                                   BigDecimal resultadoBinarioBrl, Instant criadaEm, Instant fechadaEm,
                                   BigDecimal moedasPagas, SessaoResponse sessao) {

        static OperacaoResponse de(Registro r) {
            OperacaoEstudo o = r.operacao();
            return new OperacaoResponse(o.getId(), o.getSimbolo(), o.getDirecao(), o.getTipo(), o.getModo(), o.getStatus(),
                    o.getMotivo(), o.getNocionalBrl(), o.getPrecoAgoraJogo(), o.getPrecoPrevistoJogo(),
                    o.getPrecoEntrada(), o.getPrecoSaida(), o.getResultadoBrutoBrl(), o.getTaxasBrl(),
                    o.getResultadoLiquidoBrl(), o.getAcertouDirecao(), o.getResultadoBinarioBrl(), o.getCriadaEm(),
                    o.getFechadaEm(), o.getMoedasPagas(), r.sessao() == null ? null : SessaoResponse.de(r.sessao()));
        }
    }

    /** Resumo com a taxa de acerto ja calculada, no total de cada modo e por ativo. */
    public record ResumoResponse(ModoEstudo modoAtual, BigDecimal nocionalBrl, BigDecimal apostaBinariaBrl,
                                 Numeros simulado, Numeros real, List<PorAtivo> porAtivo) {

        public record Numeros(long operacoes, long acertos, BigDecimal taxaAcertoPercentual,
                              BigDecimal resultadoBrutoBrl, BigDecimal taxasBrl, BigDecimal resultadoLiquidoBrl,
                              BigDecimal placarBinarioBrl) {

            static Numeros de(NumerosEstudo n) {
                return new Numeros(n.operacoes(), n.acertos(), n.taxaAcertoPercentual(), n.resultadoBrutoBrl(),
                        n.taxasBrl(), n.resultadoLiquidoBrl(), n.placarBinarioBrl());
            }
        }

        /** Numeros de um ativo num modo (ex.: ETHUSDT SIMULADO). */
        public record PorAtivo(String simbolo, ModoEstudo modo, Numeros numeros) {
        }

        static ResumoResponse de(ResumoEstudo r) {
            return new ResumoResponse(r.modoAtual(), r.nocionalBrl(), r.apostaBinariaBrl(), Numeros.de(r.simulado()),
                    Numeros.de(r.real()), r.porAtivo().stream()
                    .map(a -> new PorAtivo(a.simbolo(), a.modo(), Numeros.de(a.numeros()))).toList());
        }
    }

    /**
     * Placar do jogador nas operacoes fechadas: uma entrada por ativo do estudo (BTCUSDT, ETHUSDT, PAXGUSDT, mesmo
     * zerada) e o total. {@code taxaAcertoPercentual} = acertos x 100 / (acertos + erros), nula sem nenhum dos dois;
     * {@code resultadoLiquidoBrl} = soma do resultado liquido das operacoes (0 sem nenhuma).
     */
    public record MeusAcertosResponse(List<AcertosAtivo> porAtivo, Acertos total) {

        public record Acertos(long operacoes, long acertos, long erros, long empates,
                              BigDecimal taxaAcertoPercentual, BigDecimal resultadoLiquidoBrl) {

            static Acertos de(Placar p) {
                return new Acertos(p.operacoes(), p.acertos(), p.erros(), p.empates(), p.taxaAcertoPercentual(),
                        p.resultadoLiquidoBrl());
            }
        }

        public record AcertosAtivo(String simbolo, long operacoes, long acertos, long erros, long empates,
                                   BigDecimal taxaAcertoPercentual, BigDecimal resultadoLiquidoBrl) {

            static AcertosAtivo de(AtivoEstudo ativo, Placar p) {
                return new AcertosAtivo(ativo.simbolo(), p.operacoes(), p.acertos(), p.erros(), p.empates(),
                        p.taxaAcertoPercentual(), p.resultadoLiquidoBrl());
            }
        }

        static MeusAcertosResponse de(MeusAcertos m) {
            return new MeusAcertosResponse(m.porAtivo().entrySet().stream()
                    .map(e -> AcertosAtivo.de(e.getKey(), e.getValue())).toList(), Acertos.de(m.total()));
        }
    }

    private final OperacaoEstudoService service;

    public EstudoController(OperacaoEstudoService service) {
        this.service = service;
    }

    @PostMapping("/operacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public OperacaoResponse registrar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                      @Valid @RequestBody NovaOperacao pedido) {
        return OperacaoResponse.de(service.registrar(usuarioId, pedido.sessaoId(), pedido.simbolo(),
                pedido.direcao(), pedido.precoAgora(), pedido.precoPrevisto()));
    }

    @GetMapping("/operacoes/{id}")
    public OperacaoResponse buscar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                   @PathVariable UUID id) {
        return OperacaoResponse.de(service.buscar(usuarioId, id));
    }

    /** Placar do proprio jogador por ativo; {@code sessaoId} filtra uma partida. */
    @GetMapping("/meus-acertos")
    public MeusAcertosResponse meusAcertos(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                           @RequestParam(required = false) UUID sessaoId) {
        return MeusAcertosResponse.de(service.meusAcertos(usuarioId, sessaoId));
    }

    @GetMapping("/resumo")
    public ResumoResponse resumo() {
        return ResumoResponse.de(service.resumo());
    }
}
