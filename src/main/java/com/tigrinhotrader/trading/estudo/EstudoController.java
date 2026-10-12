package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.cliente.WalletClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Estudo da sabedoria das massas: a operacao feita com cada flecha bitcoin e o resumo do estudo. */
@RestController
@RequestMapping("/jogos/flecha/estudo")
public class EstudoController {

    /** Previsao do jogador no instante do disparo. {@code tipo}: BINARIA ou PREVISAO (sem ele, PREVISAO). */
    public record NovaOperacao(@NotNull UUID sessaoId, @NotBlank String direcao, String tipo,
                               @Positive BigDecimal precoAgora, @Positive BigDecimal precoPrevisto) {
    }

    /** O que o jogador ve: a direcao, o modo e, quando fechar, quanto a operacao ganhou ou perdeu. */
    public record OperacaoResponse(UUID id, Direcao direcao, TipoPrevisao tipo, ModoEstudo modo,
                                   StatusOperacao status, String motivo, BigDecimal nocionalBrl,
                                   BigDecimal precoAgoraJogo, BigDecimal precoPrevistoJogo, BigDecimal precoEntrada,
                                   BigDecimal precoSaida, BigDecimal resultadoBrutoBrl, BigDecimal taxasBrl,
                                   BigDecimal resultadoLiquidoBrl, Boolean acertouDirecao,
                                   BigDecimal resultadoBinarioBrl, Instant criadaEm, Instant fechadaEm) {

        static OperacaoResponse de(OperacaoEstudo o) {
            return new OperacaoResponse(o.getId(), o.getDirecao(), o.getTipo(), o.getModo(), o.getStatus(),
                    o.getMotivo(), o.getNocionalBrl(), o.getPrecoAgoraJogo(), o.getPrecoPrevistoJogo(),
                    o.getPrecoEntrada(), o.getPrecoSaida(), o.getResultadoBrutoBrl(), o.getTaxasBrl(),
                    o.getResultadoLiquidoBrl(), o.getAcertouDirecao(), o.getResultadoBinarioBrl(), o.getCriadaEm(),
                    o.getFechadaEm());
        }
    }

    /** Resumo com a taxa de acerto ja calculada. */
    public record ResumoResponse(ModoEstudo modoAtual, BigDecimal nocionalBrl, BigDecimal apostaBinariaBrl,
                                 Numeros simulado, Numeros real) {

        public record Numeros(long operacoes, long acertos, BigDecimal taxaAcertoPercentual,
                              BigDecimal resultadoBrutoBrl, BigDecimal taxasBrl, BigDecimal resultadoLiquidoBrl,
                              BigDecimal placarBinarioBrl) {

            static Numeros de(NumerosEstudo n) {
                return new Numeros(n.operacoes(), n.acertos(), n.taxaAcertoPercentual(), n.resultadoBrutoBrl(),
                        n.taxasBrl(), n.resultadoLiquidoBrl(), n.placarBinarioBrl());
            }
        }

        static ResumoResponse de(ResumoEstudo r) {
            return new ResumoResponse(r.modoAtual(), r.nocionalBrl(), r.apostaBinariaBrl(), Numeros.de(r.simulado()),
                    Numeros.de(r.real()));
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
        return OperacaoResponse.de(service.registrar(usuarioId, pedido.sessaoId(), pedido.direcao(), pedido.tipo(),
                pedido.precoAgora(), pedido.precoPrevisto()));
    }

    @GetMapping("/operacoes/{id}")
    public OperacaoResponse buscar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                   @PathVariable UUID id) {
        return OperacaoResponse.de(service.buscar(usuarioId, id));
    }

    @GetMapping("/resumo")
    public ResumoResponse resumo() {
        return ResumoResponse.de(service.resumo());
    }
}
