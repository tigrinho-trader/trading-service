package com.tigrinhotrader.trading.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A conversao de moedas_pagas e aposta_moedas para numeric: so no Postgres, so se a coluna ainda for inteira, sem
 * derrubar a subida.
 */
class AjusteEsquemaTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AjusteEsquema ajuste = new AjusteEsquema(jdbc);

    /** O banco responde com este nome de produto e este tipo das duas colunas (vazio = sem a tabela). */
    private void banco(String produto, List<String> tipoColuna) throws Exception {
        banco(produto, tipoColuna, tipoColuna);
    }

    /** O banco responde com este nome de produto e o tipo de cada coluna. */
    private void banco(String produto, List<String> tipoMoedasPagas, List<String> tipoApostaMoedas) throws Exception {
        Connection conexao = mock(Connection.class);
        DatabaseMetaData metadados = mock(DatabaseMetaData.class);
        when(conexao.getMetaData()).thenReturn(metadados);
        when(metadados.getDatabaseProductName()).thenReturn(produto);
        // doAnswer/doReturn: restubar com when() chamaria a resposta anterior
        doAnswer(i -> i.<ConnectionCallback<?>>getArgument(0).doInConnection(conexao))
                .when(jdbc).execute(any(ConnectionCallback.class));
        doReturn(tipoMoedasPagas).when(jdbc).queryForList(AjusteEsquema.TIPO_MOEDAS_PAGAS, String.class);
        doReturn(tipoApostaMoedas).when(jdbc).queryForList(AjusteEsquema.TIPO_APOSTA_MOEDAS, String.class);
    }

    @Test
    void postgresComAsColunasInteirasConverteParaNumeric() throws Exception {
        banco("PostgreSQL", List.of("integer"));

        ajuste.run(null);

        verify(jdbc).execute("alter table ordens drop constraint if exists ordens_tipo_check");
        verify(jdbc).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
        verify(jdbc).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);
    }

    @Test
    void bancoDeProducaoDeHojeSoConverteAApostaUmaVez() throws Exception {
        // moedas_pagas ja convertida antes; aposta_moedas ainda inteira (aposta fixa de 100)
        banco("PostgreSQL", List.of("numeric"), List.of("integer"));
        ajuste.run(null);
        verify(jdbc, never()).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
        verify(jdbc).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);

        // na subida seguinte as duas ja sao numeric: idempotente
        banco("PostgreSQL", List.of("numeric"), List.of("numeric"));
        ajuste.run(null);
        verify(jdbc).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);
    }

    @Test
    void colunaJaConvertidaOuTabelaQueNaoExisteNaoMexe() throws Exception {
        banco("PostgreSQL", List.of("numeric"));
        ajuste.moedasPagasComCentavos();
        ajuste.apostaMoedasComCentavos();
        banco("PostgreSQL", List.of());
        ajuste.moedasPagasComCentavos();
        ajuste.apostaMoedasComCentavos();

        verify(jdbc, never()).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
        verify(jdbc, never()).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);
    }

    @Test
    void foraDoPostgresNemConsulta() throws Exception {
        banco("H2", List.of("INTEGER"));

        ajuste.moedasPagasComCentavos();
        ajuste.apostaMoedasComCentavos();

        verify(jdbc, never()).queryForList(anyString(), eq(String.class));
        verify(jdbc, never()).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
        verify(jdbc, never()).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);
    }

    @Test
    void falhaNoAjusteNaoDerrubaASubida() throws Exception {
        banco("PostgreSQL", List.of("integer"));
        doThrow(new DataAccessResourceFailureException("sem permissao")).when(jdbc).execute(anyString());

        assertThatCode(() -> ajuste.run(null)).doesNotThrowAnyException();

        // uma falha nao impede a tentativa da outra
        verify(jdbc).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
        verify(jdbc).execute(AjusteEsquema.APOSTA_MOEDAS_DECIMAL);
    }
}
