package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.servico.OrdemService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** O usuario vem no cabecalho X-Usuario-Id, preenchido pelo api-gateway a partir do JWT. */
@RestController
@RequestMapping("/ordens")
public class OrdemController {

    private final OrdemService ordemService;

    public OrdemController(OrdemService ordemService) {
        this.ordemService = ordemService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdemResponse criar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                               @Valid @RequestBody NovaOrdemRequest pedido) {
        if (pedido.tipo() == TipoOrdem.BARREIRA) {
            return OrdemResponse.de(ordemService.criarBarreira(usuarioId, pedido.simbolo(), pedido.alvo(),
                    pedido.valor(), pedido.duracaoSegundos()));
        }
        return OrdemResponse.de(ordemService.criar(usuarioId, pedido.simbolo(), pedido.tipo(), pedido.modoOuPadrao(),
                pedido.valor(), pedido.duracaoSegundos()));
    }

    @GetMapping
    public List<OrdemResponse> listar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId) {
        return ordemService.listar(usuarioId).stream().map(OrdemResponse::de).toList();
    }

    /** Modos, multiplicadores e limites do jogo. Nao depende do jogador. */
    @GetMapping("/regras")
    public RegrasResponse regras() {
        return RegrasResponse.atuais();
    }

    /** Parametros da aposta "sem toque" e a volatilidade atual de cada ativo, pro frontend precificar ao vivo. */
    @GetMapping("/barreira")
    public BarreiraResponse barreira(@RequestParam(defaultValue = "BTCUSDT,ETHUSDT,SOLUSDT") List<String> simbolos) {
        return BarreiraResponse.de(simbolos, ordemService::volatilidade);
    }

    @GetMapping("/{id}")
    public OrdemResponse buscar(@RequestHeader(WalletClient.CABECALHO_USUARIO) String usuarioId,
                                @PathVariable UUID id) {
        return OrdemResponse.de(ordemService.buscar(usuarioId, id));
    }
}
