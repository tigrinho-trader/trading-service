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

    /**
     * O que as sessoes abertas ainda comprometem: custo menos o que ja voltou nelas (as apostas de mercado ja
     * liquidadas). Negativo quando a rodada esta no lucro: esse ganho ja pode ser usado sem sair do campo.
     */
    @Query("select coalesce(sum(s.custo - s.ganhos), 0) from SessaoFlecha s where s.usuarioId = :usuarioId "
            + "and s.status = com.tigrinhotrader.trading.flecha.dominio.StatusSessao.ABERTA")
    BigDecimal somarComprometidoEmAberto(@Param("usuarioId") String usuarioId);
}
