package com.tigrinhotrader.trading.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrdemRepository extends JpaRepository<Ordem, UUID> {

    List<Ordem> findByUsuarioIdOrderByCriadaEmDesc(String usuarioId);

    Optional<Ordem> findByIdAndUsuarioId(UUID id, String usuarioId);

    List<Ordem> findByStatusAndExpiraEmLessThanEqual(StatusOrdem status, Instant limite);

    List<Ordem> findBySimboloAndTipoAndStatus(String simbolo, TipoOrdem tipo, StatusOrdem status);

    /** Soma do valor apostado em rodadas ainda abertas: e o saldo "comprometido" do jogador. */
    @Query("select coalesce(sum(o.valor), 0) from Ordem o "
            + "where o.usuarioId = :usuarioId and o.status = com.tigrinhotrader.trading.dominio.StatusOrdem.ABERTA")
    BigDecimal somarValorEmAberto(@Param("usuarioId") String usuarioId);
}
