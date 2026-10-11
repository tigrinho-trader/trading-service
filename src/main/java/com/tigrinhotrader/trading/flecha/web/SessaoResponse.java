package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.StatusSessao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Estado da sessao. {@code flechas} = restantes por tipo; resultado = ganhos - custo. */
public record SessaoResponse(UUID id, String campo, StatusSessao status, BigDecimal custo, BigDecimal ganhos,
                             BigDecimal resultado, Map<String, Integer> flechas, Map<String, Integer> compradas,
                             Map<String, Integer> disparadas, int acertos, Instant criadaEm, Instant encerradaEm) {

    public static SessaoResponse de(SessaoFlecha s) {
        return new SessaoResponse(s.getId(), s.getCampo().slug(), s.getStatus(), s.getCusto(), s.getGanhos(),
                s.resultado(), s.getRestantes().comoMapa(), s.getCompradas().comoMapa(), s.getDisparadas().comoMapa(),
                s.getAcertos(), s.getCriadaEm(), s.getEncerradaEm());
    }
}
