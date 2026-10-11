package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.servico.ConflitoException;
import com.tigrinhotrader.trading.servico.RecursoNaoEncontradoException;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.RitmoExcedidoException;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TratadorErros {

    @ExceptionHandler(RegraNegocioException.class)
    ProblemDetail regraNegocio(RegraNegocioException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(RecursoNaoEncontradoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflitoException.class)
    ProblemDetail conflito(ConflitoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Dois pedidos mexeram na mesma sessao/ordem ao mesmo tempo (@Version): o cliente tenta de novo. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail alteracaoSimultanea(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Alterado por outro pedido ao mesmo tempo, tente de novo");
    }

    @ExceptionHandler(RitmoExcedidoException.class)
    ProblemDetail ritmoExcedido(RitmoExcedidoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(ServicoIndisponivelException.class)
    ProblemDetail indisponivel(ServicoIndisponivelException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail semUsuario(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Cabecalho " + e.getHeaderName() + " ausente");
    }
}
