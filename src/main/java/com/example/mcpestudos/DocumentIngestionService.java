package com.example.mcpestudos;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><b>Escopo.</b> O nome do arquivo diz a quem o documento pertence, e so o que for reconhecido e indexado:
 * {@code protocolo-<qualquer>.pdf} e orientacao geral da clinica; {@code paciente-<id>-<qualquer>.pdf} e o
 * historico do paciente {@code <id>}. Qualquer outro nome <b>nao e indexado</b>: sem saber de quem e o
 * documento, o seguro e nao oferece-lo a nenhuma busca. O escopo vai nos metadados de cada trecho
 * ({@link #META_ESCOPO}, {@link #META_PACIENTE_ID}) e e o que {@link ConsultorClinicoTools} filtra.
 *
 * <p>A indexacao tolera falhas: um PDF corrompido ou sem texto nao impede os demais, e o resultado fica
 * disponivel em {@link #getStatus()} para a ferramenta MCP explicar ao usuario o que aconteceu.
 */
@Service
public class DocumentIngestionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    static final String META_ESCOPO = "escopo";
    static final String META_PACIENTE_ID = "paciente_id";
    static final String ESCOPO_PROTOCOLO = "PROTOCOLO";
    static final String ESCOPO_PACIENTE = "PACIENTE";

    private static final Pattern NOME_PROTOCOLO = Pattern.compile("^protocolo-.+\\.pdf$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOME_PACIENTE = Pattern.compile("^paciente-(\\d{1,18})-.+\\.pdf$",
            Pattern.CASE_INSENSITIVE);

    /** A quem um documento pertence: {@code pacienteId} so existe no escopo {@code PACIENTE}. */
    record Classificacao(String escopo, Long pacienteId) {
    }

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
        status.set(new StatusIndexacao(StatusIndexacao.Estado.INDEXANDO, 0, 0, 0, 0, 0));

        Resource[] pdfs;
        try {
            pdfs = resolver.getResources(props.docsLocation());
        } catch (IOException e) {
            log.error("Nao foi possivel listar os PDFs em {}", props.docsLocation(), e);
            status.set(new StatusIndexacao(StatusIndexacao.Estado.FALHOU, 0, 1, 0, 0, 0));
            return status.get();
        }

        int arquivos = 0;
        int falhas = 0;
        int semTexto = 0;
        int naoClassificados = 0;
        int trechos = 0;
        for (Resource pdf : pdfs) {
            Optional<Classificacao> classificacao = classificar(pdf.getFilename());
            if (classificacao.isEmpty()) {
                naoClassificados++;
                log.warn("{} ignorado: o nome deve comecar com 'protocolo-' ou 'paciente-<id>-' para que se "
                        + "saiba a quem o documento pertence", pdf.getFilename());
                continue;
            }
            try {
                int gravados = indexar(pdf, classificacao.get());
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
        status.set(new StatusIndexacao(estado, arquivos, falhas, semTexto, naoClassificados, trechos));
        return status.get();
    }

    private static Map<String, Object> comEscopo(Map<String, Object> metadados, Classificacao classificacao) {
        Map<String, Object> copia = new HashMap<>(metadados);
        copia.put(META_ESCOPO, classificacao.escopo());
        if (classificacao.pacienteId() != null) {
            copia.put(META_PACIENTE_ID, classificacao.pacienteId());
        }
        return copia;
    }

    /**
     * Colapsa sequencias de espacos e tabs em um unico espaco, preservando as quebras de linha. A extracao de
     * PDF (texto justificado, colunas) costuma deixar longas sequencias de espacos, que poluem o embedding e
     * o texto devolvido ao modelo.
     */
    static String normalizarEspacos(String texto) {
        return texto == null ? "" : texto.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\R ?", "\n");
    }

    /**
     * Decide o escopo de um PDF pelo nome. Maiusculas e minusculas valem igual.
     *
     * @return o escopo, ou vazio se o nome nao segue nenhum dos dois padroes (o PDF nao deve ser indexado)
     */
    static Optional<Classificacao> classificar(String nomeArquivo) {
        if (nomeArquivo == null) {
            return Optional.empty();
        }
        if (NOME_PROTOCOLO.matcher(nomeArquivo).matches()) {
            return Optional.of(new Classificacao(ESCOPO_PROTOCOLO, null));
        }
        Matcher paciente = NOME_PACIENTE.matcher(nomeArquivo);
        if (paciente.matches()) {
            return Optional.of(new Classificacao(ESCOPO_PACIENTE, Long.parseLong(paciente.group(1))));
        }
        return Optional.empty();
    }

    /** @return quantos trechos foram gravados para o PDF */
    private int indexar(Resource pdf, Classificacao classificacao) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
        List<Document> paginas = new PagePdfDocumentReader(pdf, config).get().stream()
                .map(p -> new Document(normalizarEspacos(p.getText()), comEscopo(p.getMetadata(), classificacao)))
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
