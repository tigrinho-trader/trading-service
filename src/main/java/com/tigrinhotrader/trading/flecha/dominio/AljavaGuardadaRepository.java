package com.tigrinhotrader.trading.flecha.dominio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AljavaGuardadaRepository extends JpaRepository<AljavaGuardada, UUID> {

    Optional<AljavaGuardada> findByUsuarioIdAndEscopo(String usuarioId, String escopo);

    List<AljavaGuardada> findByUsuarioId(String usuarioId);
}
