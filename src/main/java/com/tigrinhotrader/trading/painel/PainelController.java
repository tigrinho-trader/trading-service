package com.tigrinhotrader.trading.painel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Painel do super-admin (pelo gateway em /api/painel/jogo/**): regras do jogo e ganhos e perdas por perfil. So
 * responde com o cabecalho {@value #CABECALHO}, que o gateway poe depois de validar o login do painel (e apaga
 * se vier do cliente).
 */
@RestController
@RequestMapping("/painel")
public class PainelController {

    public static final String CABECALHO = "X-Painel-Admin";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    /** Regras em vigor e as do ambiente (para o painel mostrar o padrao de cada campo). */
    public record ConfigResponse(Configuracao atual, Configuracao ambiente) {
    }

    private final ConfiguracaoService configuracao;
    private final ResultadosPainel resultados;
    private final Clock relogio;

    public PainelController(ConfiguracaoService configuracao, ResultadosPainel resultados, Clock relogio) {
        this.configuracao = configuracao;
        this.resultados = resultados;
        this.relogio = relogio;
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return new ConfigResponse(configuracao.atual(), configuracao.doAmbiente());
    }

    @PutMapping("/config")
    public ConfigResponse alterar(@RequestBody ConfiguracaoService.Alteracao alteracao) {
        configuracao.alterar(alteracao);
        return config();
    }

    /**
     * Ganhos e perdas por perfil. {@code dias}: 1 = hoje (desde a meia-noite de Sao Paulo), 7 = hoje e os 6 dias
     * antes, e assim por diante; 0 ou sem o parametro = tudo.
     */
    @GetMapping("/resultados")
    public ResultadosPainel.Resumo resultados(@RequestParam(required = false) Integer dias) {
        return resultados.calcular(desde(dias, relogio.instant()));
    }

    static Instant desde(Integer dias, Instant agora) {
        if (dias == null || dias <= 0) {
            return Instant.EPOCH;
        }
        return agora.atZone(FUSO).toLocalDate().minusDays(dias - 1L).atStartOfDay(FUSO).toInstant();
    }

    /** Recusa (403) qualquer /painel/** sem o cabecalho do gateway. */
    @Configuration
    static class Guarda implements WebMvcConfigurer, HandlerInterceptor {

        @Override
        public void addInterceptors(InterceptorRegistry registro) {
            registro.addInterceptor(this).addPathPatterns("/painel", "/painel/**");
        }

        @Override
        public boolean preHandle(HttpServletRequest pedido, HttpServletResponse resposta, Object handler)
                throws java.io.IOException {
            if (StringUtils.hasText(pedido.getHeader(CABECALHO))) {
                return true;
            }
            resposta.sendError(HttpStatus.FORBIDDEN.value(), "Só o painel do super-admin");
            return false;
        }
    }
}
