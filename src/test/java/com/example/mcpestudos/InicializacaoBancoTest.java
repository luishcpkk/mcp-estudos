package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class InicializacaoBancoTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @TempDir
    Path dir;

    private JdbcClient novoBanco() {
        return JdbcClient.create(new DriverManagerDataSource(
                "jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true"));
    }

    private void executarScripts(String... scripts) {
        var populator = new ResourceDatabasePopulator();
        for (String s : scripts) {
            populator.addScript(new ClassPathResource(s));
        }
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(new DriverManagerDataSource(
                "jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true"));
    }

    private static List<String> colunas(JdbcClient jdbc) {
        return jdbc.sql("PRAGMA table_info(consultas)").query((rs, n) -> rs.getString("name")).list();
    }

    @Test
    void migraBancoAntigoSemAsColunasDeCancelamentoEEhIdempotente() {
        JdbcClient jdbc = novoBanco();
        // Tabelas como existiam antes desta mudanca.
        jdbc.sql("CREATE TABLE dentistas (id INTEGER PRIMARY KEY, nome TEXT NOT NULL, especialidade TEXT NOT NULL)").update();
        jdbc.sql("CREATE TABLE pacientes (id INTEGER PRIMARY KEY, nome TEXT NOT NULL, telefone TEXT)").update();
        jdbc.sql("CREATE TABLE consultas (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "dentista_id INTEGER NOT NULL REFERENCES dentistas(id), "
                + "paciente_id INTEGER NOT NULL REFERENCES pacientes(id), data TEXT NOT NULL, "
                + "hora TEXT NOT NULL, procedimento TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'AGENDADA')").update();
        assertThat(colunas(jdbc)).doesNotContain("motivo_cancelamento", "cancelada_em");

        var inicializacao = new InicializacaoBanco(jdbc, Clock.systemUTC());
        inicializacao.run(null);
        inicializacao.run(null); // segunda execucao nao pode falhar

        assertThat(colunas(jdbc)).contains("motivo_cancelamento", "cancelada_em");
        // Sem dentista 1 / paciente 1 no banco, a consulta de exemplo e simplesmente ignorada.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM consultas").query(Integer.class).single()).isZero();
    }

    @Test
    void semeiaConsultaNoProximoDiaUtilNoFusoDaClinica() {
        JdbcClient jdbc = novoBanco();
        executarScripts("schema.sql", "data.sql");
        // 2026-11-13 (sexta) 23:30 em Sao Paulo = 2026-11-14 02:30 UTC: o 'now' do SQLite ja estaria no sabado.
        Clock sextaNoite = Clock.fixed(Instant.parse("2026-11-14T02:30:00Z"), SP);

        var inicializacao = new InicializacaoBanco(jdbc, sextaNoite);
        inicializacao.run(null);
        inicializacao.run(null); // nao duplica

        List<String> datas = jdbc.sql("SELECT data FROM consultas").query(String.class).list();
        assertThat(datas).containsExactly("2026-11-16"); // segunda: pula o fim de semana
    }

    @Test
    void naoSemeiaSeJaHaConsultas() {
        JdbcClient jdbc = novoBanco();
        executarScripts("schema.sql", "data.sql");
        jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                + "VALUES (2, 2, '2026-12-01', '10:00', 'Canal')").update();

        new InicializacaoBanco(jdbc, Clock.systemUTC()).run(null);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM consultas").query(Integer.class).single()).isEqualTo(1);
    }
}
