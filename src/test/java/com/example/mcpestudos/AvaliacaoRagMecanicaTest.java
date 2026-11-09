package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.example.mcpestudos.AvaliadorRag.Limiar;
import com.example.mcpestudos.AvaliadorRag.Relatorio;
import com.example.mcpestudos.PerguntaRag.EvidenciaEsperada;
import com.example.mcpestudos.PerguntaRag.Tipo;

/**
 * Parte da avaliacao do RAG que NAO depende de modelo: sempre roda, em qualquer maquina, sem Ollama.
 *
 * <p>Usa o pipeline real (PDFs reais, divisao em trechos, {@link SimpleVectorStore}, a ferramenta
 * {@code consultarBaseClinica}) com um embedding falso (so por palavras). Garante que:
 * <ul>
 *   <li>o conjunto de perguntas e coerente com os PDFs (toda evidencia existe de fato no documento e cabe em um unico trecho);</li>
 *   <li>a regra de escopo (privacidade entre pacientes) vale em todas as perguntas;</li>
 *   <li>a matematica das metricas (Recall@k, MRR, limiar) esta certa, para que o relatorio com o modelo real possa ser confiado.</li>
 * </ul>
 * A QUALIDADE da recuperacao com o modelo real e medida por {@link AvaliacaoRagOllamaTest}.
 */
class AvaliacaoRagMecanicaTest {

    private static final String DOCS = "classpath:avaliacao-rag/docs/*.pdf";

    @TempDir
    Path dir;

    final List<PerguntaRag> perguntas = PerguntaRag.carregar();
    final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    VectorStore store;
    ConsultorClinicoTools tools;

    @BeforeEach
    void montarPipeline() {
        store = SimpleVectorStore.builder(new EmbeddingFalso()).build();
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("avaliacao.db") + "?foreign_keys=true");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO pacientes (id, nome, telefone) VALUES (104, 'Carlos Almeida', '(31) 90000-0104')").update();

        var props = new RagProperties(DOCS, 500, 8, 0.0);
        var ingestao = new DocumentIngestionService(store, resolver, props);
        StatusIndexacao status = ingestao.ingerirDocumentos();
        assertThat(status.estado()).isEqualTo(StatusIndexacao.Estado.PRONTA);
        assertThat(status.arquivos()).as("PDFs de avaliacao indexados").isEqualTo(2);
        assertThat(status.arquivosNaoClassificados()).isZero();
        tools = new ConsultorClinicoTools(store, ingestao, props, jdbc);
    }

    // ---------- o conjunto de perguntas ----------

    @Test
    void oConjuntoTemTamanhoEEstruturaCoerentes() throws IOException {
        long perguntasDeAvaliacao = perguntas.stream().filter(p -> p.tipo() != Tipo.ESCOPO).count();
        assertThat(perguntasDeAvaliacao).as("perguntas de avaliacao (fora as verificacoes de escopo)").isBetween(15L, 20L);
        assertThat(perguntas.stream().map(PerguntaRag::id).collect(Collectors.toSet()))
                .as("ids unicos").hasSameSizeAs(perguntas);

        Set<String> pdfs = nomesDosPdfs();
        for (PerguntaRag p : perguntas) {
            assertThat(p.pergunta()).as("%s: texto da pergunta", p.id()).isNotBlank();
            assertThat(p.pacienteId()).as("%s: pacienteId conhecido", p.id()).isIn(null, 1L, 2L, 3L, 104L);
            switch (p.tipo()) {
                case POSITIVA -> {
                    assertThat(p.evidencias()).as("%s: precisa de ao menos uma evidencia", p.id()).isNotEmpty();
                    for (EvidenciaEsperada e : p.evidencias()) {
                        assertThat(pdfs).as("%s: fonte da evidencia", p.id()).contains(e.fonte());
                        assertThat(e.trecho()).as("%s: frase da evidencia", p.id()).isNotBlank();
                        // Evidencia em historico de paciente so e alcancavel se a pergunta informa esse paciente.
                        DocumentIngestionService.classificar(e.fonte()).filter(c -> c.pacienteId() != null)
                                .ifPresent(c -> assertThat(p.pacienteId())
                                        .as("%s: a evidencia esta no historico do paciente %s, entao a pergunta deve informar esse pacienteId",
                                                p.id(), c.pacienteId())
                                        .isEqualTo(c.pacienteId()));
                    }
                }
                case NEGATIVA -> assertThat(p.evidencias()).as("%s: negativa nao tem evidencia", p.id()).isEmpty();
                case ESCOPO -> assertThat(p.fontesProibidas()).as("%s: escopo precisa de fonte proibida", p.id()).isNotEmpty();
            }
        }
    }

    @Test
    void cadaEvidenciaExisteDeFatoNoTextoDoPdf() throws IOException {
        for (PerguntaRag p : perguntas) {
            for (EvidenciaEsperada e : p.evidencias()) {
                String textoDoPdf = AvaliadorRag.normalizar(textoCompleto(e.fonte()));
                assertThat(textoDoPdf)
                        .as("%s: a frase \"%s\" deveria existir em %s (o PDF mudou ou a frase tem erro de digitacao?)",
                                p.id(), e.trecho(), e.fonte())
                        .contains(AvaliadorRag.normalizar(e.trecho()));
            }
        }
    }

    @Test
    void osPdfsDeTesteSaoLidosSemPerdaDeLetras() throws IOException {
        // Guarda contra um defeito real: PDFs gerados com fontes que usam ligaduras tipograficas ("ti", "fi", "fl"),
        // como Calibri/Carlito no LibreOffice, fazem o leitor do Spring AI (PDFBox) perder letras dos titulos
        // ("Periodontite" vira "periodontte"). Estas palavras so aparecem inteiras se o PDF foi gerado sem ligaduras.
        String protocolo = AvaliadorRag.normalizar(textoCompleto("protocolo-emergencia-dor.pdf"));
        for (String palavra : List.of("objetivo", "periodontite", "sintomatica", "antibioticos", "radiografia",
                "antisseptico", "diagnostico", "inflamatorio")) {
            assertThat(protocolo).as("palavra \"%s\" no protocolo (ligadura quebrando a extracao?)", palavra).contains(palavra);
        }
        String historico = AvaliadorRag.normalizar(textoCompleto("paciente-104-carlos_almeida.pdf"));
        for (String palavra : List.of("identificacao", "ultima", "diagnostico")) {
            assertThat(historico).as("palavra \"%s\" no historico (ligadura quebrando a extracao?)", palavra).contains(palavra);
        }
    }

    @Test
    void cadaEvidenciaCabeEmUmUnicoTrechoIndexado() {
        // Se a frase fosse cortada entre dois trechos, nenhum modelo conseguiria "acerta-la": seria erro do dataset.
        List<Document> trechosIndexados = store.similaritySearch(SearchRequest.builder().query("protocolo").topK(1000).build());
        assertThat(trechosIndexados).isNotEmpty();
        for (PerguntaRag p : perguntas) {
            for (EvidenciaEsperada e : p.evidencias()) {
                boolean cabe = trechosIndexados.stream()
                        .filter(t -> e.fonte().equals(t.getMetadata().get("file_name")))
                        .anyMatch(t -> AvaliadorRag.normalizar(t.getText()).contains(AvaliadorRag.normalizar(e.trecho())));
                assertThat(cabe).as("%s: \"%s\" deveria estar inteira dentro de UM trecho de %s", p.id(), e.trecho(), e.fonte())
                        .isTrue();
            }
        }
    }

    // ---------- a regra de escopo no pipeline real ----------

    @Test
    void oEscopoNuncaVazaEmNenhumaPerguntaDoConjunto() {
        Relatorio relatorio = AvaliadorRag.avaliar("embedding-falso", 8, perguntas,
                p -> tools.consultarBaseClinica(p.pergunta(), p.pacienteId()));

        assertThat(relatorio.violacoesDeEscopo()).as("violacoes de privacidade").isEmpty();
        relatorio.resultados().stream().filter(r -> r.pergunta().tipo() == Tipo.ESCOPO)
                .forEach(r -> assertThat(r.consultaOk()).as("%s foi consultada com sucesso", r.pergunta().id()).isTrue());
    }

    // ---------- o avaliador ----------

    @Test
    void oAvaliadorProcessaTodasAsPerguntasEGravaORelatorio() throws IOException {
        Relatorio relatorio = AvaliadorRag.avaliar("embedding-falso", 8, perguntas,
                p -> tools.consultarBaseClinica(p.pergunta(), p.pacienteId()));

        assertThat(relatorio.resultados()).hasSameSizeAs(perguntas);
        assertThat(relatorio.positivas()).allSatisfy(r -> assertThat(r.consultaOk()).isTrue());

        Path arquivo = Path.of("target/avaliacao-rag/relatorio-mecanica.md");
        AvaliadorRag.gravar(relatorio, arquivo);
        String md = Files.readString(arquivo, StandardCharsets.UTF_8);
        assertThat(md).contains("# Avaliacao do RAG: embedding-falso", "## Resumo", "Recall@4", "MRR",
                "## Limiar de similaridade sugerido", "## Resultado por pergunta", "| P01 |", "| S02 |");
    }

    @Test
    void asMetricasSaoCalculadasCorretamente() {
        // Cenario controlado, sem modelo: A acha na 2a posicao, B na 1a, C nao acha; D e uma pergunta sem resposta.
        List<PerguntaRag> cenario = List.of(
                positiva("A", "alfa"), positiva("B", "beta"), positiva("C", "gama"), negativa("D"));
        Relatorio relatorio = AvaliadorRag.avaliar("cenario", 5, cenario, p -> switch (p.id()) {
            case "A" -> ok(trecho("nada a ver", 0.62), trecho("aqui fala de ALFA, com acento: álfa", 0.50));
            case "B" -> ok(trecho("o BETA esta aqui", 0.80));
            case "C" -> ok(trecho("irrelevante", 0.40));
            default -> ok(trecho("qualquer coisa", 0.35));
        });

        assertThat(relatorio.recallPorPergunta(1)).isCloseTo(1.0 / 3, within(1e-9));   // so B
        assertThat(relatorio.recallPorPergunta(3)).isCloseTo(2.0 / 3, within(1e-9));   // A e B
        assertThat(relatorio.mrr()).isCloseTo((0.5 + 1.0 + 0.0) / 3, within(1e-9));    // 1/2, 1/1, nunca

        // Positivos recuperados: A (0.50) e B (0.80) -> o menor e 0.50. Negativa D: topo 0.35. Separa limpo.
        Limiar limiar = relatorio.sugerirLimiar();
        assertThat(limiar.separavel()).isTrue();
        assertThat(limiar.menorPositivo()).isCloseTo(0.50, within(1e-9));
        assertThat(limiar.maiorNegativo()).isCloseTo(0.35, within(1e-9));
        assertThat(limiar.sugerido()).isCloseTo(0.425, within(0.006));
        assertThat(relatorio.paraMarkdown()).as("com separacao limpa, o relatorio recomenda o limiar").contains("Para aplicar");

        // Agora a negativa tem um trecho MAIS parecido (0.60) que o pior positivo (0.50): nao ha limiar limpo.
        Relatorio sobreposto = AvaliadorRag.avaliar("sobreposto", 5, cenario, p -> switch (p.id()) {
            case "A" -> ok(trecho("nada a ver", 0.62), trecho("fala de alfa", 0.50));
            case "B" -> ok(trecho("o beta", 0.80));
            case "C" -> ok(trecho("irrelevante", 0.40));
            default -> ok(trecho("qualquer coisa", 0.60));
        });
        Limiar semSeparacao = sobreposto.sugerirLimiar();
        assertThat(semSeparacao.separavel()).isFalse();
        assertThat(semSeparacao.explicacao()).contains("SEM separacao").contains("cortaria 1 de 2");
        assertThat(sobreposto.paraMarkdown()).as("sem separacao limpa, NAO se recomenda aplicar um limiar que corta respostas boas")
                .doesNotContain("Para aplicar");
    }

    @Test
    void oAvaliadorDetectaVazamentoDeEscopo() {
        PerguntaRag semPaciente = new PerguntaRag("S", Tipo.ESCOPO, "alergias?", null, null, List.of("proibido.pdf"));
        PerguntaRag comPaciente = new PerguntaRag("T", Tipo.POSITIVA, "alergias?", 1L,
                List.of(new EvidenciaEsperada("x.pdf", "qualquer")), null);

        Relatorio relatorio = AvaliadorRag.avaliar("vazando", 5, List.of(semPaciente, comPaciente), p -> ok(
                new TrechoClinico("proibido.pdf", 1, 0.9, "PACIENTE", 104L, "historico"),
                new TrechoClinico("paciente-104-x.pdf", 1, 0.8, "PACIENTE", 104L, "historico do outro")));

        assertThat(relatorio.violacoesDeEscopo()).anySatisfy(v -> assertThat(v).contains("S:", "arquivo proibido proibido.pdf"));
        assertThat(relatorio.violacoesDeEscopo()).anySatisfy(v -> assertThat(v).contains("S:", "sem pacienteId"));
        assertThat(relatorio.violacoesDeEscopo()).anySatisfy(v -> assertThat(v).contains("T:", "paciente 104 na consulta do paciente 1"));
    }

    // ---------- auxiliares ----------

    private static PerguntaRag positiva(String id, String frase) {
        return new PerguntaRag(id, Tipo.POSITIVA, "pergunta " + id, null, List.of(new EvidenciaEsperada("a.pdf", frase)), null);
    }

    private static PerguntaRag negativa(String id) {
        return new PerguntaRag(id, Tipo.NEGATIVA, "pergunta " + id, null, null, null);
    }

    private static TrechoClinico trecho(String texto, double similaridade) {
        return new TrechoClinico("a.pdf", 1, similaridade, "PROTOCOLO", null, texto);
    }

    private static Resultado<ConsultaClinica> ok(TrechoClinico... trechos) {
        return Resultado.ok(new ConsultaClinica("p", null, List.of(trechos), "aviso"));
    }

    private Set<String> nomesDosPdfs() throws IOException {
        Set<String> nomes = new HashSet<>();
        for (Resource r : resolver.getResources(DOCS)) {
            nomes.add(r.getFilename());
        }
        return nomes;
    }

    private String textoCompleto(String arquivo) throws IOException {
        for (Resource r : resolver.getResources(DOCS)) {
            if (arquivo.equals(r.getFilename())) {
                return new PagePdfDocumentReader(r).get().stream().map(Document::getText).collect(Collectors.joining("\n"));
            }
        }
        throw new IllegalStateException("PDF de avaliacao nao encontrado: " + arquivo);
    }
}
