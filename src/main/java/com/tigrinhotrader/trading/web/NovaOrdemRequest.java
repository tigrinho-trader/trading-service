package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.dominio.TipoOrdem;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record NovaOrdemRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{2,20}", message = "simbolo invalido") String simbolo,
        @NotNull TipoOrdem tipo,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal valor,
        @NotNull Integer duracaoSegundos) {
}
