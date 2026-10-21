package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.flecha.servico.FlechaProperties;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService.ResultadoTiro;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jogo Flecha Certeira. O usuario vem no cabecalho X-Usuario-Id (api-gateway); so /regras e publico. Flechas de
 * mercado nao passam por /tiros: sao gastas pelo estudo ({@code POST /jogos/flecha/estudo/operacoes}).
 */
@RestController
@RequestMapping("/jogos/flecha")
public class FlechaController {

    /** Moedas que a flecha rendeu e a sessao atualizada. */
    public record TiroResponse(int ganhosTiro, SessaoResponse sessao) {
    }

    private final SessaoFlechaService service;
    private final FlechaProperties propriedades;

    public FlechaController(SessaoFlechaService service, FlechaProperties propriedades) {
        this.service = service;
        this.propriedades = propriedades;
    }

    @GetMapping("/regras")
    public RegrasFlechaResponse regras() {
        return RegrasFlechaResponse.atuais(propriedades.taxaDevolucao());
    }

    /** Flechas guardadas de rodadas anteriores: as comuns por campo e as de mercado, que valem em todos. */
    @GetMapping("/aljava")
    public AljavaGuardadaResponse aljava(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId) {
        return AljavaGuardadaResponse.de(service.guardadas(usuarioId));
    }

    @PostMapping("/sessoes")
    @ResponseStatus(HttpStatus.CREATED)
    public SessaoResponse criar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                @Valid @RequestBody FlechaRequests.NovaSessao pedido) {
        return SessaoResponse.de(service.criar(usuarioId, pedido.campo(), pedido.flechas(), pedido.mercado()));
    }

    /** Sessao aberta do jogador (200) ou nenhuma (204). */
    @GetMapping("/sessoes/atual")
    public ResponseEntity<SessaoResponse> atual(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId) {
        return service.atual(usuarioId)
                .map(s -> ResponseEntity.ok(SessaoResponse.de(s)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/sessoes/{id}/tiros")
    public TiroResponse disparar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                 @PathVariable UUID id, @Valid @RequestBody FlechaRequests.Tiro tiro) {
        ResultadoTiro resultado = service.disparar(usuarioId, id, tiro.flecha(), tiro.acertos());
        return new TiroResponse(resultado.ganhosTiro(), SessaoResponse.de(resultado.sessao()));
    }

    @PostMapping("/sessoes/{id}/flechas")
    public SessaoResponse comprar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                  @PathVariable UUID id, @Valid @RequestBody FlechaRequests.CompraFlechas compra) {
        return SessaoResponse.de(service.comprar(usuarioId, id, compra.flecha(), compra.quantidade(),
                compra.valor()));
    }

    @PostMapping("/sessoes/{id}/encerrar")
    public SessaoResponse encerrar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                   @PathVariable UUID id) {
        return SessaoResponse.de(service.encerrar(usuarioId, id));
    }
}
