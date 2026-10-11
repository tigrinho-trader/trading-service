package com.tigrinhotrader.trading.flecha.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessaoFlechaRepository extends JpaRepository<SessaoFlecha, UUID> {

    Optional<SessaoFlecha> findFirstByUsuarioIdAndStatus(String usuarioId, StatusSessao status);

    Optional<SessaoFlecha> findByIdAndUsuarioId(UUID id, String usuarioId);

    /** Sessoes abertas paradas desde antes do limite (o agendador encerra). */
    List<SessaoFlecha> findByStatusAndUltimaAtividadeEmBefore(StatusSessao status, Instant limite);

    /** Custo das sessoes abertas: dinheiro ja comprometido no jogo, como as ordens abertas. */
    @Query("select coalesce(sum(s.custo), 0) from SessaoFlecha s where s.usuarioId = :usuarioId "
            + "and s.status = com.tigrinhotrader.trading.flecha.dominio.StatusSessao.ABERTA")
    BigDecimal somarCustoEmAberto(@Param("usuarioId") String usuarioId);
}
