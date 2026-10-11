package com.tigrinhotrader.trading.servico;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.OrdemRepository;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Saldo que o jogador ainda pode apostar: saldo da carteira menos o que ja esta comprometido em rodadas
 * abertas (ordens) e em sessoes abertas do Flecha Certeira. Usado por todos os jogos.
 */
@Component
public class SaldoDisponivel {

    private final WalletClient walletClient;
    private final OrdemRepository ordens;
    private final SessaoFlechaRepository sessoesFlecha;

    public SaldoDisponivel(WalletClient walletClient, OrdemRepository ordens, SessaoFlechaRepository sessoesFlecha) {
        this.walletClient = walletClient;
        this.ordens = ordens;
        this.sessoesFlecha = sessoesFlecha;
    }

    public BigDecimal calcular(String usuarioId) {
        return walletClient.saldo(usuarioId)
                .subtract(ordens.somarValorEmAberto(usuarioId))
                .subtract(sessoesFlecha.somarCustoEmAberto(usuarioId));
    }

    /** Recusa (422) se o disponivel nao cobre o valor. */
    public void exigir(String usuarioId, BigDecimal valor) {
        BigDecimal disponivel = calcular(usuarioId);
        if (disponivel.compareTo(valor) < 0) {
            throw new RegraNegocioException("Saldo insuficiente: disponivel " + disponivel.max(BigDecimal.ZERO));
        }
    }
}
