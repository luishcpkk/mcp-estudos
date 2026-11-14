package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class AuditoriaToolsTest {

    @TempDir
    Path dir;

    JdbcClient jdbc;
    AuditoriaDeAcessos auditoria;
    AuditoriaTools tools;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        jdbc = JdbcClient.create(ds);
        auditoria = new AuditoriaDeAcessos(jdbc, Clock.fixed(Instant.parse("2026-11-09T13:30:15Z"),
                ZoneId.of("America/Sao_Paulo")));
        tools = new AuditoriaTools(auditoria, jdbc);
    }

    @Test
    void ferramentaEhExpostaComPacienteIdObrigatorioELimiteOpcional() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("acessosAoHistorico");
        String schema = callbacks[0].getToolDefinition().inputSchema();
        assertThat(schema).contains("\"pacienteId\"").contains("\"limite\"");
        assertThat(schema).containsPattern("\"required\"\\s*:\\s*\\[\\s*\"pacienteId\"\\s*\\]");
        assertThat(callbacks[0].getToolDefinition().description())
                .contains("Nao mostra o texto das perguntas").contains("quem acessou");
    }

    @Test
    void listaOsAcessosDoMaisNovoParaOMaisAntigoComTotalEAviso() {
        auditoria.registrar(104L, "consultasDoPaciente", 2);
        auditoria.registrar(104L, "consultarBaseClinica", 3);
        auditoria.registrar(1L, "consultasDoPaciente", 1);

        Resultado<RegistroDeAcessos> r = tools.acessosAoHistorico(104L, null);

        assertThat(r.sucesso()).isTrue();
        RegistroDeAcessos registro = r.dados();
        assertThat(registro.pacienteId()).isEqualTo(104L);
        assertThat(registro.total()).isEqualTo(2);
        assertThat(registro.acessos()).extracting(AcessoAoHistorico::ferramenta)
                .containsExactly("consultarBaseClinica", "consultasDoPaciente");
        assertThat(registro.acessos().get(0).acessadoEm()).isEqualTo("2026-11-09T10:30:15-03:00");
        // O aviso impede o modelo de afirmar QUEM acessou: o servidor nao tem login.
        assertThat(registro.aviso()).contains("QUANDO").contains("QUEM").contains("nao tem login").contains("Nao afirme");
    }

    @Test
    void pacienteSemAcessosVoltaListaVazia() {
        Resultado<RegistroDeAcessos> r = tools.acessosAoHistorico(2L, null);

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().total()).isZero();
        assertThat(r.dados().acessos()).isEmpty();
    }

    @Test
    void limitePadraoE20EOTotalContaTudo() {
        for (int i = 0; i < 25; i++) {
            auditoria.registrar(1L, "consultasDoPaciente", i);
        }

        RegistroDeAcessos padrao = tools.acessosAoHistorico(1L, null).dados();
        RegistroDeAcessos tres = tools.acessosAoHistorico(1L, 3).dados();

        assertThat(padrao.acessos()).hasSize(AuditoriaTools.LIMITE_PADRAO).hasSize(20);
        assertThat(padrao.total()).isEqualTo(25);
        assertThat(tres.acessos()).extracting(AcessoAoHistorico::trechos).containsExactly(24, 23, 22);
        assertThat(tres.total()).isEqualTo(25);
    }

    @Test
    void recusaPedidoInvalido() {
        assertThat(tools.acessosAoHistorico(null, null).erro()).contains("ID do paciente");
        assertThat(tools.acessosAoHistorico(999L, null).erro()).contains("Paciente nao encontrado: 999");
        for (Integer limite : new Integer[] {0, -1, AuditoriaTools.LIMITE_MAXIMO + 1}) {
            Resultado<RegistroDeAcessos> r = tools.acessosAoHistorico(1L, limite);
            assertThat(r.sucesso()).as("limite=%s", limite).isFalse();
            assertThat(r.erro()).contains("entre 1 e 100");
        }
        assertThat(tools.acessosAoHistorico(1L, AuditoriaTools.LIMITE_MAXIMO).sucesso()).isTrue();
        assertThat(tools.acessosAoHistorico(1L, 1).sucesso()).isTrue();
    }

    @Test
    void lerORegistroNaoCriaNovoRegistro() {
        auditoria.registrar(1L, "consultasDoPaciente", 0);

        tools.acessosAoHistorico(1L, null);
        tools.acessosAoHistorico(1L, null);

        assertThat(auditoria.total(1L)).isEqualTo(1);
    }
}
