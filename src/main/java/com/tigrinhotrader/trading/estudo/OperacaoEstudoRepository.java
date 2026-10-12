package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperacaoEstudoRepository extends JpaRepository<OperacaoEstudo, UUID> {

    Optional<OperacaoEstudo> findByIdAndUsuarioId(UUID id, String usuarioId);

    long countBySessaoId(UUID sessaoId);

    Optional<OperacaoEstudo> findFirstByUsuarioIdOrderByCriadaEmDesc(String usuarioId);

    long countByModoAndStatusNotAndCriadaEmGreaterThanEqual(ModoEstudo modo, StatusOperacao status, Instant desde);

    long countByModoAndStatusIn(ModoEstudo modo, Collection<StatusOperacao> status);

    List<OperacaoEstudo> findByStatusAndAbertaEmBefore(StatusOperacao status, Instant limite);

    List<OperacaoEstudo> findByStatusAndCriadaEmBefore(StatusOperacao status, Instant limite);

    /** Resultado liquido somado das operacoes fechadas desde um instante. */
    @Query("select coalesce(sum(o.resultadoLiquidoBrl), 0) from OperacaoEstudo o where o.modo = :modo "
            + "and o.status = com.tigrinhotrader.trading.estudo.StatusOperacao.FECHADA and o.criadaEm >= :desde")
    BigDecimal somarResultadoDesde(@Param("modo") ModoEstudo modo, @Param("desde") Instant desde);

    /** Numeros do estudo num modo: operacoes fechadas, acertos de direcao e somas em reais. */
    @Query("select new com.tigrinhotrader.trading.estudo.NumerosEstudo(count(o), "
            + "coalesce(sum(case when o.acertouDirecao = true then 1L else 0L end), 0L), "
            + "coalesce(sum(o.resultadoBrutoBrl), 0), coalesce(sum(o.taxasBrl), 0), "
            + "coalesce(sum(o.resultadoLiquidoBrl), 0)) "
            + "from OperacaoEstudo o where o.modo = :modo "
            + "and o.status = com.tigrinhotrader.trading.estudo.StatusOperacao.FECHADA")
    NumerosEstudo resumir(@Param("modo") ModoEstudo modo);
}
