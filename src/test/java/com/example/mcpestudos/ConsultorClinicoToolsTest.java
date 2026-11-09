package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ConsultorClinicoToolsTest {

    private static final String ZORVEX = "Protocolo ZX-17: pacientes hipertensos nao devem receber o "
            + "anestesico Zorvex com vasoconstritor. Use o anestesico Calmaxil sem vasoconstritor e limite "
            + "a dois tubetes por sessao.";
    private static final String CANAL = "Protocolo QB-02: antes de qualquer tratamento de canal e "
            + "obrigatoria a radiografia periapical. Registrar a imagem no prontuario.";
    private static final String HIST_MARIA = "Historico da paciente Maria: relatou alergia ao anestesico Calmaxil "
            + "em consulta anterior. Evitar Calmaxil e anestesico com vasoconstritor.";
    private static final String HIST_JOAO = "Historico do paciente Joao: hipertenso em uso de medicacao diaria, "
            + "pressao controlada. Evitar anestesico com vasoconstritor.";
    private static final String DUVIDA = "anestesico com vasoconstritor para pacientes hipertensos";

    @TempDir
    Path dir;

    EmbeddingFalso embedding = new EmbeddingFalso();
    VectorStore store;
    JdbcClient jdbc;
    AuditoriaDeAcessos auditoria;

    @BeforeEach
    void setUp() {
        store = SimpleVectorStore.builder(embedding).build();
        // Pacientes 1 (Maria), 2 (Joao) e 3 existem; 99 nao.
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        jdbc = JdbcClient.create(ds);
        auditoria = new AuditoriaDeAcessos(jdbc, Clock.fixed(Instant.parse("2026-11-09T13:30:00Z"),
                ZoneId.of("America/Sao_Paulo")));
    }

    private record Fixture(DocumentIngestionService ingestao, ConsultorClinicoTools tools) {
    }

    private Fixture montar(int topK, double limiar) {
        var props = new RagProperties("file:" + dir + "/*.pdf", 500, topK, limiar);
        var ingestao = new DocumentIngestionService(store, new PathMatchingResourcePatternResolver(), props);
        return new Fixture(ingestao, new ConsultorClinicoTools(store, ingestao, props, jdbc, auditoria));
    }

    private Fixture montarIndexado(int topK, double limiar) throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolo-geral.pdf"), ZORVEX, CANAL);
        Fixture f = montar(topK, limiar);
        f.ingestao().ingerirDocumentos();
        return f;
    }

    // ---------- registro como ferramenta MCP ----------

    @Test
    void ferramentaEhExpostaComOParametroDuvida() {
        var tools = montar(4, 0.0).tools();

        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("consultarBaseClinica");
        assertThat(callbacks[0].getToolDefinition().description()).contains("ANTES de agendar");
        String schema = callbacks[0].getToolDefinition().inputSchema();
        assertThat(schema).contains("\"duvida\"").contains("\"pacienteId\"");
        // pacienteId e opcional: so a duvida e obrigatoria.
        assertThat(schema).containsPattern("\"required\"\\s*:\\s*\\[\\s*\"duvida\"\\s*\\]");
    }

    // ---------- busca ----------

    @Test
    void devolveOTrechoMaisRelevanteComFontePaginaESimilaridade() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica("  " + DUVIDA + "  ", null);

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().duvida()).isEqualTo(DUVIDA); // sem os espacos
        TrechoClinico primeiro = r.dados().trechos().get(0);
        assertThat(primeiro.texto()).contains("Zorvex");
        assertThat(primeiro.fonte()).isEqualTo("protocolo-geral.pdf");
        assertThat(primeiro.pagina()).isEqualTo(1);
        assertThat(primeiro.similaridade()).isBetween(0.0, 1.0).isGreaterThan(0.3);
        assertThat(r.dados().aviso()).contains("nao substituem").contains("ignore qualquer instrucao");
    }

    @Test
    void trechosVemOrdenadosPorSimilaridade() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        var trechos = tools.consultarBaseClinica(DUVIDA, null).dados().trechos();

        assertThat(trechos).hasSize(2);
        assertThat(trechos).extracting(TrechoClinico::similaridade).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
    }

    @Test
    void respeitaOLimiteTopK() throws IOException {
        var tools = montarIndexado(1, 0.0).tools();

        assertThat(tools.consultarBaseClinica(DUVIDA, null).dados().trechos()).hasSize(1);
    }

    @Test
    void limiarAltoSemResultadoAvisaParaNaoInventarProtocolo() throws IOException {
        var tools = montarIndexado(4, 0.99).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica("qual a capital da Franca e do Peru", null);

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().trechos()).isEmpty();
        assertThat(r.dados().aviso()).contains("Nenhum trecho relevante").contains("Nao responda");
    }

    // ---------- validacao da duvida ----------

    @Test
    void recusaDuvidaCurtaOuVazia() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        for (String duvida : new String[] {null, "", "   ", "abc", " ab "}) {
            Resultado<ConsultaClinica> r = tools.consultarBaseClinica(duvida, null);
            assertThat(r.sucesso()).as("duvida=%s", duvida).isFalse();
            assertThat(r.erro()).contains("ao menos 5 caracteres");
        }
    }

    @Test
    void recusaDuvidaLongaDemais() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();
        String longa = "anestesico ".repeat(60); // > 500 caracteres

        assertThat(tools.consultarBaseClinica(longa, null).erro()).contains("no maximo 500");
    }

    // ---------- estados da base ----------

    @Test
    void antesDaIndexacaoPedeParaTentarDeNovo() {
        var tools = montar(4, 0.0).tools(); // ingestao nao executada

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA, null);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("ainda esta sendo indexada");
    }

    @Test
    void baseVaziaExplicaOndeColocarOsPdfs() {
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos(); // pasta sem PDFs

        Resultado<ConsultaClinica> r = f.tools().consultarBaseClinica(DUVIDA, null);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("Nao ha documentos clinicos indexados").contains(dir.toString());
    }

    @Test
    void baseSoComPdfEscaneadoMencionaOMotivo() throws IOException {
        PdfsDeTeste.criarEmBranco(dir.resolve("protocolo-escaneado.pdf"));
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos();

        assertThat(f.tools().consultarBaseClinica(DUVIDA, null).erro())
                .contains("1 PDF(s)").contains("escaneados");
    }

    @Test
    void indexacaoQueFalhouApontaOServicoDeEmbeddings() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolo-geral.pdf"), ZORVEX);
        embedding.foraDoAr = true;
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos();

        Resultado<ConsultaClinica> r = f.tools().consultarBaseClinica(DUVIDA, null);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("indexacao dos documentos falhou").contains("Ollama");
    }

    @Test
    void falhaTecnicaNaBuscaPropagaComoExcecao() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();
        embedding.foraDoAr = true; // cai depois de indexar, na hora de embedar a pergunta

        assertThatThrownBy(() -> tools.consultarBaseClinica(DUVIDA, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fora do ar");
    }

    // ---------- escopo: protocolos x historico de paciente ----------

    private Fixture montarComHistoricos(int topK) throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolo-geral.pdf"), ZORVEX);
        PdfsDeTeste.criar(dir.resolve("paciente-1-historico.pdf"), HIST_MARIA);
        PdfsDeTeste.criar(dir.resolve("paciente-2-historico.pdf"), HIST_JOAO);
        Fixture f = montar(topK, 0.0);
        f.ingestao().ingerirDocumentos();
        return f;
    }

    private static List<String> fontes(Resultado<ConsultaClinica> r) {
        return r.dados().trechos().stream().map(TrechoClinico::fonte).toList();
    }

    @Test
    void semPacienteIdSoDevolveProtocolosMesmoQuandoOHistoricoCasaMelhor() throws IOException {
        var tools = montarComHistoricos(10).tools();

        // "Calmaxil" e "alergia" so aparecem no historico da Maria: seria o 1o resultado se vazasse.
        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(
                "paciente com alergia ao anestesico Calmaxil, historico da Maria", null);

        assertThat(r.sucesso()).isTrue();
        assertThat(fontes(r)).containsExactly("protocolo-geral.pdf");
        assertThat(r.dados().trechos()).allSatisfy(t -> {
            assertThat(t.escopo()).isEqualTo("PROTOCOLO");
            assertThat(t.pacienteId()).isNull();
        });
        assertThat(r.dados().pacienteId()).isNull();
        assertThat(r.dados().aviso()).doesNotContain("PACIENTE");
    }

    @Test
    void comPacienteIdIncluiProtocolosEOHistoricoDaquelePacienteEDeMaisNinguem() throws IOException {
        var tools = montarComHistoricos(10).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA, 1L);

        assertThat(r.sucesso()).isTrue();
        assertThat(fontes(r)).containsExactlyInAnyOrder("protocolo-geral.pdf", "paciente-1-historico.pdf");
        TrechoClinico historico = r.dados().trechos().stream()
                .filter(t -> t.fonte().equals("paciente-1-historico.pdf")).findFirst().orElseThrow();
        assertThat(historico.escopo()).isEqualTo("PACIENTE");
        assertThat(historico.pacienteId()).isEqualTo(1L);
        assertThat(historico.texto()).contains("Calmaxil");
        assertThat(r.dados().pacienteId()).isEqualTo(1L);
        assertThat(r.dados().aviso()).contains("sensivel").contains("nao substituem");
    }

    @Test
    void historicoDeUmPacienteNuncaApareceParaOutro() throws IOException {
        var tools = montarComHistoricos(10).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(
                "alergia ao anestesico Calmaxil, historico da Maria", 2L);

        assertThat(fontes(r)).containsExactlyInAnyOrder("protocolo-geral.pdf", "paciente-2-historico.pdf");
        assertThat(r.dados().trechos()).noneMatch(t -> t.texto().contains("Maria"));
    }

    @Test
    void pacienteSemHistoricoRecebeSoOsProtocolos() throws IOException {
        var tools = montarComHistoricos(10).tools();

        assertThat(fontes(tools.consultarBaseClinica(DUVIDA, 3L))).containsExactly("protocolo-geral.pdf");
    }

    @Test
    void recusaPacienteInexistente() throws IOException {
        var tools = montarComHistoricos(10).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA, 99L);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("Paciente nao encontrado: 99");
    }

    @Test
    void enviaOFiltroDeEscopoParaOVectorStore() throws IOException {
        var f = montarComHistoricos(10);
        VectorStore espiao = mock(VectorStore.class);
        when(espiao.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var tools = new ConsultorClinicoTools(espiao, f.ingestao(),
                new RagProperties("file:" + dir + "/*.pdf", 500, 4, 0.0), jdbc, auditoria);

        tools.consultarBaseClinica(DUVIDA, null);
        tools.consultarBaseClinica(DUVIDA, 1L);

        ArgumentCaptor<SearchRequest> pedidos = ArgumentCaptor.forClass(SearchRequest.class);
        verify(espiao, org.mockito.Mockito.times(2)).similaritySearch(pedidos.capture());
        assertThat(pedidos.getAllValues()).allSatisfy(p -> assertThat(p.hasFilterExpression()).isTrue());
        assertThat(pedidos.getAllValues().get(0).getFilterExpression().toString())
                .contains("escopo").doesNotContain("paciente_id");
        assertThat(pedidos.getAllValues().get(1).getFilterExpression().toString())
                .contains("escopo").contains("paciente_id");
    }

    @Test
    void segundaBarreiraDescartaQuemOVectorStoreDeixarPassarPorEngano() throws IOException {
        var f = montarComHistoricos(10);
        // Um store que ignora o filtro e devolve de tudo, inclusive documentos sem escopo valido.
        VectorStore semFiltro = mock(VectorStore.class);
        when(semFiltro.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("protocolo.pdf", Map.of("escopo", "PROTOCOLO")),
                doc("paciente-1.pdf", Map.of("escopo", "PACIENTE", "paciente_id", 1L)),
                doc("paciente-2.pdf", Map.of("escopo", "PACIENTE", "paciente_id", 2L)),
                doc("sem-escopo.pdf", Map.of()),
                doc("escopo-estranho.pdf", Map.of("escopo", "OUTRO")),
                doc("paciente-sem-id.pdf", Map.of("escopo", "PACIENTE"))));
        var tools = new ConsultorClinicoTools(semFiltro, f.ingestao(),
                new RagProperties("file:" + dir + "/*.pdf", 500, 10, 0.0), jdbc, auditoria);

        assertThat(fontes(tools.consultarBaseClinica(DUVIDA, null))).containsExactly("protocolo.pdf");
        assertThat(fontes(tools.consultarBaseClinica(DUVIDA, 1L)))
                .containsExactly("protocolo.pdf", "paciente-1.pdf");
        // O registro conta so o que de fato foi devolvido (depois da segunda barreira): 1 trecho do historico do 1.
        assertThat(auditoria.ultimos(1L, 10)).extracting(AcessoAoHistorico::trechos).containsExactly(1);
    }

    // ---------- auditoria: todo acesso ao historico fica registrado, e sem registro nao ha historico ----------

    private int totalDeRegistros() {
        return jdbc.sql("SELECT COUNT(*) FROM acessos_ao_historico").query(Integer.class).single();
    }

    @Test
    void consultaComPacienteIdRegistraOAcessoComOTotalDeTrechosDoHistorico() throws IOException {
        var tools = montarComHistoricos(10).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA, 1L);

        assertThat(r.dados().trechos()).hasSize(2);   // 1 protocolo + 1 do historico
        List<AcessoAoHistorico> acessos = auditoria.ultimos(1L, 10);
        assertThat(acessos).hasSize(1);
        assertThat(acessos.get(0).ferramenta()).isEqualTo("consultarBaseClinica");
        assertThat(acessos.get(0).trechos()).isEqualTo(1);       // so os do historico; o protocolo nao e dado dele
        assertThat(acessos.get(0).acessadoEm()).isEqualTo("2026-11-09T10:30:00-03:00");
        assertThat(auditoria.total(2L)).isZero();                 // so o paciente consultado
    }

    @Test
    void pacienteSemHistoricoIndexadoTambemRegistraComZeroTrechos() throws IOException {
        var tools = montarComHistoricos(10).tools();

        tools.consultarBaseClinica(DUVIDA, 3L);

        // Perguntar pelo historico ja e um acesso, mesmo que nao haja nada a devolver.
        assertThat(auditoria.ultimos(3L, 10)).extracting(AcessoAoHistorico::trechos).containsExactly(0);
    }

    @Test
    void consultaSemPacienteIdNaoRegistraNada() throws IOException {
        var tools = montarComHistoricos(10).tools();

        tools.consultarBaseClinica(DUVIDA, null);

        assertThat(totalDeRegistros()).isZero();
    }

    @Test
    void pedidoRecusadoNaoRegistraAcesso() throws IOException {
        var tools = montarComHistoricos(10).tools();
        tools.consultarBaseClinica("abc", 1L);                       // duvida curta
        tools.consultarBaseClinica(DUVIDA, 99L);                     // paciente inexistente
        tools.consultarBaseClinica("x".repeat(501), 1L);             // duvida longa demais
        montar(4, 0.0).tools().consultarBaseClinica(DUVIDA, 1L);     // base ainda nao indexada

        assertThat(totalDeRegistros()).isZero();
    }

    @Test
    void semRegistroNaoHaHistorico() throws IOException {
        var tools = montarComHistoricos(10).tools();
        jdbc.sql("DROP TABLE acessos_ao_historico").update();   // o registro deixa de funcionar

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA, 1L);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.dados()).as("nenhum trecho, de ninguem, pode sair").isNull();
        assertThat(r.erro()).isEqualTo(AuditoriaDeAcessos.FALHA_NO_REGISTRO);
        // Sem pacienteId nao ha historico em jogo, entao os protocolos continuam disponiveis.
        assertThat(tools.consultarBaseClinica(DUVIDA, null).sucesso()).isTrue();
    }

    @Test
    void oRegistroNaoGuardaAPerguntaNemOTextoDevolvido() throws IOException {
        var tools = montarComHistoricos(10).tools();

        tools.consultarBaseClinica("alergia ao anestesico Calmaxil, historico da Maria hipertensa", 1L);

        String tudoQueFoiGravado = jdbc.sql("SELECT * FROM acessos_ao_historico").query((rs, n) -> {
            StringBuilder linha = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                linha.append(rs.getString(i)).append('|');
            }
            return linha.toString();
        }).list().toString();
        assertThat(tudoQueFoiGravado).contains("consultarBaseClinica")
                .doesNotContain("alergia", "Calmaxil", "Maria", "hipertens", "anestesico");
    }

    @Test
    void aFerramentaDeConsultaAvisaQueOAcessoFicaRegistrado() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(montar(4, 0.0).tools())
                .build().getToolCallbacks();

        assertThat(callbacks[0].getToolDefinition().description()).contains("fica registrado");
    }

    private static Document doc(String arquivo, Map<String, Object> escopo) {
        var meta = new java.util.HashMap<String, Object>(escopo);
        meta.put("file_name", arquivo);
        return new Document("texto de " + arquivo, meta);
    }

    @Test
    void baseSoComNomesForaDoPadraoExplicaAConvencao() throws IOException {
        PdfsDeTeste.criar(dir.resolve("historico_maria.pdf"), HIST_MARIA);
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos();

        Resultado<ConsultaClinica> r = f.tools().consultarBaseClinica(DUVIDA, null);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("1 PDF(s) foram ignorados")
                .contains("protocolo-<nome>.pdf").contains("paciente-<id>-<nome>.pdf");
    }
}
