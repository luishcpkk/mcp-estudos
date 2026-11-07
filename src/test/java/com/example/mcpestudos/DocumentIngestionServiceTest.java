package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.example.mcpestudos.StatusIndexacao.Estado;

/** PDFs sinteticos reais + SimpleVectorStore real; so o modelo de embeddings e falso. */
class DocumentIngestionServiceTest {

    private static final String ZORVEX = "Protocolo ZX-17: pacientes hipertensos nao devem receber o "
            + "anestesico Zorvex com vasoconstritor. Use o anestesico Calmaxil sem vasoconstritor e limite "
            + "a dois tubetes por sessao.";
    private static final String CANAL = "Protocolo QB-02: antes de qualquer tratamento de canal é "
            + "obrigatória a radiografia periapical. Registrar a imagem no prontuário.";
    private static final String CLAREAMENTO = "Protocolo GE-09: o clareamento dental com peroxido Alvorin "
            + "é contraindicado durante a gestação e a amamentação.";

    @TempDir
    Path dir;

    EmbeddingFalso embedding = new EmbeddingFalso();
    VectorStore store;

    @BeforeEach
    void setUp() {
        store = SimpleVectorStore.builder(embedding).build();
    }

    private DocumentIngestionService servico(int chunkSize) {
        var props = new RagProperties("file:" + dir + "/*.pdf", chunkSize, 4, 0.0);
        return new DocumentIngestionService(store, new PathMatchingResourcePatternResolver(), props);
    }

    private List<Document> buscar(String consulta, int topK) {
        return store.similaritySearch(SearchRequest.builder().query(consulta).topK(topK).build());
    }

    @Test
    void normalizaEspacosSemPerderQuebrasDeLinha() {
        assertThat(DocumentIngestionService.normalizarEspacos("Protocolo  ZX-17:   pacientes\t\thipertensos  \n   devem  evitar"))
                .isEqualTo("Protocolo ZX-17: pacientes hipertensos\ndevem evitar");
        assertThat(DocumentIngestionService.normalizarEspacos("linha um\n\nlinha dois")).isEqualTo("linha um\n\nlinha dois");
        assertThat(DocumentIngestionService.normalizarEspacos(null)).isEmpty();
    }

    @Test
    void estadoInicialEhNaoIniciada() {
        assertThat(servico(500).getStatus().estado()).isEqualTo(Estado.NAO_INICIADA);
    }

    @Test
    void indexaOsPdfsDaPastaPreservandoArquivoEPagina() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX, CANAL);
        PdfsDeTeste.criar(dir.resolve("gestantes.pdf"), CLAREAMENTO);

        StatusIndexacao status = servico(500).ingerirDocumentos();

        assertThat(status.estado()).isEqualTo(Estado.PRONTA);
        assertThat(status.arquivos()).isEqualTo(2);
        assertThat(status.arquivosComFalha()).isZero();
        assertThat(status.trechos()).isEqualTo(3); // uma pagina curta = um trecho

        Document melhor = buscar("anestesico vasoconstritor pacientes hipertensos", 1).get(0);
        assertThat(melhor.getText()).contains("Zorvex");
        assertThat(melhor.getMetadata()).containsEntry("file_name", "protocolos.pdf");
        assertThat(((Number) melhor.getMetadata().get("page_number")).intValue()).isEqualTo(1);

        Document canal = buscar("radiografia periapical antes do tratamento de canal", 1).get(0);
        assertThat(canal.getMetadata()).containsEntry("file_name", "protocolos.pdf");
        assertThat(((Number) canal.getMetadata().get("page_number")).intValue()).isEqualTo(2);
        assertThat(canal.getText()).contains("obrigatória"); // acentos sobrevivem a extracao
    }

    @Test
    void divideTextoLongoEmVariosTrechosComOsMesmosMetadados() throws IOException {
        StringBuilder longo = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longo.append("Frase ").append(i).append(" sobre higiene bucal e escovacao diaria com fio dental. ");
        }
        PdfsDeTeste.criar(dir.resolve("longo.pdf"), longo.toString());

        StatusIndexacao status = servico(100).ingerirDocumentos();

        assertThat(status.arquivos()).isEqualTo(1);
        assertThat(status.trechos()).isGreaterThan(1);
        assertThat(buscar("higiene bucal escovacao", 100))
                .hasSize(status.trechos())
                .allSatisfy(d -> assertThat(d.getMetadata()).containsEntry("file_name", "longo.pdf"));
    }

    @Test
    void pdfCorrompidoNaoImpedeOsDemais() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX);
        Files.writeString(dir.resolve("quebrado.pdf"), "isto nao e um pdf");

        StatusIndexacao status = servico(500).ingerirDocumentos();

        assertThat(status.estado()).isEqualTo(Estado.PRONTA);
        assertThat(status.arquivos()).isEqualTo(1);
        assertThat(status.arquivosComFalha()).isEqualTo(1);
        assertThat(status.trechos()).isEqualTo(1);
    }

    @Test
    void pdfSemTextoContaComoSemTextoENaoEIndexado() throws IOException {
        PdfsDeTeste.criarEmBranco(dir.resolve("escaneado.pdf"));
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX);

        StatusIndexacao status = servico(500).ingerirDocumentos();

        assertThat(status.estado()).isEqualTo(Estado.PRONTA);
        assertThat(status.arquivos()).isEqualTo(2);
        assertThat(status.arquivosSemTexto()).isEqualTo(1);
        assertThat(status.trechos()).isEqualTo(1);
    }

    @Test
    void pastaSemPdfsFicaProntaEVazia() {
        StatusIndexacao status = servico(500).ingerirDocumentos();

        assertThat(status).isEqualTo(new StatusIndexacao(Estado.PRONTA, 0, 0, 0, 0));
    }

    @Test
    void servicoDeEmbeddingsForaDoArFalhaSemDerrubarAInicializacao() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX);
        embedding.foraDoAr = true;

        StatusIndexacao status = servico(500).ingerirDocumentos(); // nao lanca

        assertThat(status.estado()).isEqualTo(Estado.FALHOU);
        assertThat(status.arquivosComFalha()).isEqualTo(1);
        assertThat(status.trechos()).isZero();
    }

    @Test
    void ingestaoRodaUmaSoVezEntaoNaoDuplicaTrechos() throws IOException {
        PdfsDeTeste.criar(dir.resolve("protocolos.pdf"), ZORVEX, CANAL);
        DocumentIngestionService servico = servico(500);

        StatusIndexacao primeira = servico.ingerirDocumentos();
        int antes = buscar("protocolo", 100).size();
        StatusIndexacao segunda = servico.ingerirDocumentos();

        assertThat(segunda).isEqualTo(primeira);
        assertThat(buscar("protocolo", 100)).hasSize(antes);
    }
}
