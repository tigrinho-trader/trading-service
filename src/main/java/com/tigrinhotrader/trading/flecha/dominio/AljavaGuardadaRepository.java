package com.tigrinhotrader.trading.flecha.dominio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AljavaGuardadaRepository extends JpaRepository<AljavaGuardada, UUID> {

    Optional<AljavaGuardada> findByUsuarioIdAndEscopo(String usuarioId, String escopo);

    List<AljavaGuardada> findByUsuarioId(String usuarioId);

    /** Aljavas com flechas antigas (ferro, ouro, diamante) ainda a reembolsar. */
    @Query("select g from AljavaGuardada g where g.flechas.iron > 0 or g.flechas.gold > 0 "
            + "or g.flechas.diamond > 0")
    List<AljavaGuardada> findComFlechasAntigas();
}
