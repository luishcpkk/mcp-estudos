package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** SQLite real em arquivo temporario, com a mesma URL (foreign_keys=true) da aplicacao. */
class AuditoriaDeAcessosTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Segunda-feira, 2026-11-09, 10:30:15 em Sao Paulo (UTC-3). */
    private static final Instant AGORA = Instant.parse("2026-11-09T13:30:15.987Z");

    @TempDir
    Path dir;

    JdbcClient jdbc;
    AuditoriaDeAcessos auditoria;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        jdbc = JdbcClient.create(ds);
        auditoria = new AuditoriaDeAcessos(jdbc, Clock.fixed(AGORA, SP));
    }

    @Test
    void registraSoOFatoComOInstanteNoFusoDaClinica() {
        boolean registrado = auditoria.registrar(104L, "consultarBaseClinica", 3);

        assertThat(registrado).isTrue();
        List<AcessoAoHistorico> acessos = auditoria.ultimos(104L, 10);
        assertThat(acessos).hasSize(1);
        AcessoAoHistorico acesso = acessos.get(0);
        assertThat(acesso.pacienteId()).isEqualTo(104L);
        assertThat(acesso.ferramenta()).isEqualTo("consultarBaseClinica");
        assertThat(acesso.trechos()).isEqualTo(3);
        // Com o deslocamento do fuso (nao so a hora local) e sem fracao de segundo.
        assertThat(acesso.acessadoEm()).isEqualTo("2026-11-09T10:30:15-03:00");
    }

    @Test
    void oInstanteSempreTemSegundosMesmoQuandoSaoZero() {
        // OffsetDateTime.toString() omitiria ":00" e o registro ficaria com dois formatos.
        var exatamente = new AuditoriaDeAcessos(jdbc, Clock.fixed(Instant.parse("2026-11-09T13:30:00Z"), SP));

        exatamente.registrar(1L, "consultasDoPaciente", 0);

        assertThat(auditoria.ultimos(1L, 1).get(0).acessadoEm()).isEqualTo("2026-11-09T10:30:00-03:00");
    }

    @Test
    void aTabelaNaoTemColunaParaAPerguntaNemParaOConteudo() {
        // O registro e so o fato. Se alguem acrescentar uma coluna de texto, este teste obriga a pensar na privacidade.
        List<String> colunas = jdbc.sql("PRAGMA table_info(acessos_ao_historico)")
                .query((rs, n) -> rs.getString("name")).list();

        assertThat(colunas).containsExactly("id", "paciente_id", "ferramenta", "acessado_em", "trechos");
    }

    @Test
    void aceitaZeroTrechosPoisPerguntarPeloHistoricoJaEUmAcesso() {
        assertThat(auditoria.registrar(1L, "consultarBaseClinica", 0)).isTrue();

        assertThat(auditoria.ultimos(1L, 10)).extracting(AcessoAoHistorico::trechos).containsExactly(0);
    }

    @Test
    void falhaFechadaDevolveFalseEmVezDeLancar() {
        // Paciente inexistente: a chave estrangeira recusa o INSERT.
        assertThat(auditoria.registrar(999L, "consultasDoPaciente", 1)).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM acessos_ao_historico").query(Integer.class).single()).isZero();

        // Banco com problema: a tabela sumiu.
        jdbc.sql("DROP TABLE acessos_ao_historico").update();
        assertThat(auditoria.registrar(1L, "consultasDoPaciente", 1)).isFalse();
    }

    @Test
    void listaOsMaisRecentesPrimeiroSoDoPacienteEPeloLimite() {
        for (int i = 1; i <= 5; i++) {
            auditoria.registrar(1L, "consultasDoPaciente", i);
        }
        auditoria.registrar(2L, "consultarBaseClinica", 9);

        assertThat(auditoria.ultimos(1L, 3)).extracting(AcessoAoHistorico::trechos).containsExactly(5, 4, 3);
        assertThat(auditoria.ultimos(1L, 100)).hasSize(5);
        assertThat(auditoria.ultimos(2L, 100)).extracting(AcessoAoHistorico::ferramenta)
                .containsExactly("consultarBaseClinica");
        assertThat(auditoria.ultimos(3L, 100)).isEmpty();
    }

    @Test
    void contaOTotalSemOLimite() {
        for (int i = 0; i < 7; i++) {
            auditoria.registrar(1L, "consultasDoPaciente", 0);
        }
        auditoria.registrar(2L, "consultasDoPaciente", 0);

        assertThat(auditoria.total(1L)).isEqualTo(7);
        assertThat(auditoria.total(2L)).isEqualTo(1);
        assertThat(auditoria.total(3L)).isZero();
    }
}
