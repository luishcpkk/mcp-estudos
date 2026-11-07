package com.example.mcpestudos;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Le os PDFs clinicos, divide o texto em trechos e grava no {@link VectorStore} na inicializacao.
 *
 * <p>Cada pagina vira um documento (preserva o numero da pagina para citar a fonte) e depois o
 * {@link TokenTextSplitter} o divide em trechos de tamanho limitado. Os metadados da pagina
 * ({@code file_name}, {@code page_number}) acompanham cada trecho.
 *
 * <p>A indexacao tolera falhas: um PDF corrompido ou sem texto nao impede os demais, e o resultado fica
 * disponivel em {@link #getStatus()} para a ferramenta MCP explicar ao usuario o que aconteceu.
 */
@Service
public class DocumentIngestionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resolver;
    private final RagProperties props;
    private final TokenTextSplitter splitter;
    private final AtomicReference<StatusIndexacao> status = new AtomicReference<>(StatusIndexacao.naoIniciada());

    public DocumentIngestionService(VectorStore vectorStore, ResourcePatternResolver resolver,
                                    RagProperties props) {
        this.vectorStore = vectorStore;
        this.resolver = resolver;
        this.props = props;
        this.splitter = TokenTextSplitter.builder().withChunkSize(props.chunkSize()).build();
    }

    @Override
    public void run(ApplicationArguments args) {
        StatusIndexacao resultado = ingerirDocumentos();
        log.info("Indexacao da base clinica: {}", resultado);
    }

    public StatusIndexacao getStatus() {
        return status.get();
    }

    /**
     * Indexa todos os PDFs de {@code clinica.rag.docs-location}. Roda uma unica vez: chamadas seguintes
     * devolvem o status atual sem reindexar (o store e em memoria e os trechos ficariam duplicados).
     */
    public synchronized StatusIndexacao ingerirDocumentos() {
        if (status.get().estado() != StatusIndexacao.Estado.NAO_INICIADA) {
            return status.get();
        }
        status.set(new StatusIndexacao(StatusIndexacao.Estado.INDEXANDO, 0, 0, 0, 0));

        Resource[] pdfs;
        try {
            pdfs = resolver.getResources(props.docsLocation());
        } catch (IOException e) {
            log.error("Nao foi possivel listar os PDFs em {}", props.docsLocation(), e);
            status.set(new StatusIndexacao(StatusIndexacao.Estado.FALHOU, 0, 1, 0, 0));
            return status.get();
        }

        int arquivos = 0;
        int falhas = 0;
        int semTexto = 0;
        int trechos = 0;
        for (Resource pdf : pdfs) {
            try {
                int gravados = indexar(pdf);
                arquivos++;
                trechos += gravados;
                if (gravados == 0) {
                    semTexto++;
                    log.warn("{} nao tem texto extraivel (PDF escaneado?) e nao foi indexado", pdf.getFilename());
                }
            } catch (RuntimeException e) {
                // Um PDF ruim (ou o servico de embeddings fora do ar) nao pode derrubar a inicializacao.
                falhas++;
                log.warn("Falha ao indexar {}: {}", pdf.getFilename(), e.toString());
            }
        }
        StatusIndexacao.Estado estado = arquivos == 0 && falhas > 0
                ? StatusIndexacao.Estado.FALHOU : StatusIndexacao.Estado.PRONTA;
        status.set(new StatusIndexacao(estado, arquivos, falhas, semTexto, trechos));
        return status.get();
    }

    /**
     * Colapsa sequencias de espacos e tabs em um unico espaco, preservando as quebras de linha. A extracao de
     * PDF (texto justificado, colunas) costuma deixar longas sequencias de espacos, que poluem o embedding e
     * o texto devolvido ao modelo.
     */
    static String normalizarEspacos(String texto) {
        return texto == null ? "" : texto.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\R ?", "\n");
    }

    /** @return quantos trechos foram gravados para o PDF */
    private int indexar(Resource pdf) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
        List<Document> paginas = new PagePdfDocumentReader(pdf, config).get().stream()
                .map(p -> new Document(normalizarEspacos(p.getText()), p.getMetadata()))
                .toList();
        List<Document> trechos = splitter.apply(paginas).stream()
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .toList();
        if (!trechos.isEmpty()) {
            vectorStore.add(trechos);
        }
        return trechos.size();
    }
}
