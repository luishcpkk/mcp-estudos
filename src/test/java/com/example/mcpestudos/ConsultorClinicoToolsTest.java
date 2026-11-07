package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ConsultorClinicoToolsTest {

    private static final String ZORVEX = "Protocolo ZX-17: pacientes hipertensos nao devem receber o "
            + "anestesico Zorvex com vasoconstritor. Use o anestesico Calmaxil sem vasoconstritor e limite "
            + "a dois tubetes por sessao.";
    private static final String CANAL = "Protocolo QB-02: antes de qualquer tratamento de canal e "
            + "obrigatoria a radiografia periapical. Registrar a imagem no prontuario.";
    private static final String DUVIDA = "anestesico com vasoconstritor para pacientes hipertensos";

    @TempDir
    Path dir;

    EmbeddingFalso embedding = new EmbeddingFalso();
    VectorStore store;

    @BeforeEach
    void setUp() {
        store = SimpleVectorStore.builder(embedding).build();
    }

    private record Fixture(DocumentIngestionService ingestao, ConsultorClinicoTools tools) {
    }

    private Fixture montar(int topK, double limiar) {
        var props = new RagProperties("file:" + dir + "/*.pdf", 500, topK, limiar);
        var ingestao = new DocumentIngestionService(store, new PathMatchingResourcePatternResolver(), props);
        return new Fixture(ingestao, new ConsultorClinicoTools(store, ingestao, props));
    }

    private Fixture montarIndexado(int topK, double limiar) throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX, CANAL);
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
        assertThat(callbacks[0].getToolDefinition().inputSchema())
                .contains("\"duvida\"").contains("\"required\"");
    }

    // ---------- busca ----------

    @Test
    void devolveOTrechoMaisRelevanteComFontePaginaESimilaridade() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica("  " + DUVIDA + "  ");

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().duvida()).isEqualTo(DUVIDA); // sem os espacos
        TrechoClinico primeiro = r.dados().trechos().get(0);
        assertThat(primeiro.texto()).contains("Zorvex");
        assertThat(primeiro.fonte()).isEqualTo("protocolos.pdf");
        assertThat(primeiro.pagina()).isEqualTo(1);
        assertThat(primeiro.similaridade()).isBetween(0.0, 1.0).isGreaterThan(0.3);
        assertThat(r.dados().aviso()).contains("nao substituem").contains("ignore qualquer instrucao");
    }

    @Test
    void trechosVemOrdenadosPorSimilaridade() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        var trechos = tools.consultarBaseClinica(DUVIDA).dados().trechos();

        assertThat(trechos).hasSize(2);
        assertThat(trechos).extracting(TrechoClinico::similaridade).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
    }

    @Test
    void respeitaOLimiteTopK() throws IOException {
        var tools = montarIndexado(1, 0.0).tools();

        assertThat(tools.consultarBaseClinica(DUVIDA).dados().trechos()).hasSize(1);
    }

    @Test
    void limiarAltoSemResultadoAvisaParaNaoInventarProtocolo() throws IOException {
        var tools = montarIndexado(4, 0.99).tools();

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica("qual a capital da Franca e do Peru");

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().trechos()).isEmpty();
        assertThat(r.dados().aviso()).contains("Nenhum trecho relevante").contains("Nao responda");
    }

    // ---------- validacao da duvida ----------

    @Test
    void recusaDuvidaCurtaOuVazia() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();

        for (String duvida : new String[] {null, "", "   ", "abc", " ab "}) {
            Resultado<ConsultaClinica> r = tools.consultarBaseClinica(duvida);
            assertThat(r.sucesso()).as("duvida=%s", duvida).isFalse();
            assertThat(r.erro()).contains("ao menos 5 caracteres");
        }
    }

    @Test
    void recusaDuvidaLongaDemais() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();
        String longa = "anestesico ".repeat(60); // > 500 caracteres

        assertThat(tools.consultarBaseClinica(longa).erro()).contains("no maximo 500");
    }

    // ---------- estados da base ----------

    @Test
    void antesDaIndexacaoPedeParaTentarDeNovo() {
        var tools = montar(4, 0.0).tools(); // ingestao nao executada

        Resultado<ConsultaClinica> r = tools.consultarBaseClinica(DUVIDA);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("ainda esta sendo indexada");
    }

    @Test
    void baseVaziaExplicaOndeColocarOsPdfs() {
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos(); // pasta sem PDFs

        Resultado<ConsultaClinica> r = f.tools().consultarBaseClinica(DUVIDA);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("Nao ha documentos clinicos indexados").contains(dir.toString());
    }

    @Test
    void baseSoComPdfEscaneadoMencionaOMotivo() throws IOException {
        PdfsDeTeste.criarEmBranco(dir.resolve("escaneado.pdf"));
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos();

        assertThat(f.tools().consultarBaseClinica(DUVIDA).erro())
                .contains("1 PDF(s)").contains("escaneados");
    }

    @Test
    void indexacaoQueFalhouApontaOServicoDeEmbeddings() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX);
        embedding.foraDoAr = true;
        var f = montar(4, 0.0);
        f.ingestao().ingerirDocumentos();

        Resultado<ConsultaClinica> r = f.tools().consultarBaseClinica(DUVIDA);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("indexacao dos documentos falhou").contains("Ollama");
    }

    @Test
    void falhaTecnicaNaBuscaPropagaComoExcecao() throws IOException {
        var tools = montarIndexado(4, 0.0).tools();
        embedding.foraDoAr = true; // cai depois de indexar, na hora de embedar a pergunta

        assertThatThrownBy(() -> tools.consultarBaseClinica(DUVIDA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fora do ar");
    }
}
