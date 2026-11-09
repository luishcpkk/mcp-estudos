package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.mcpestudos.AvaliadorRag.Relatorio;

/**
 * Avaliacao do RAG com o MODELO REAL de embeddings (o mesmo de {@code application.properties}, via Ollama).
 *
 * <p>Sobe a aplicacao inteira (so sem o servidor MCP), indexa os dois PDFs de
 * {@code src/test/resources/avaliacao-rag/docs}, faz as perguntas de {@code avaliacao-rag/perguntas.json}
 * pela ferramenta {@code consultarBaseClinica} e mede Recall@k, MRR e o limiar de similaridade sugerido.
 * O relatorio completo vai para o console e para {@code target/avaliacao-rag/relatorio-<modelo>.md}.
 *
 * <p><b>So roda se o Ollama estiver no ar com o modelo instalado</b>; sem isso o teste e PULADO (nao falha),
 * com a explicacao no console. Para rodar:
 * <pre>
 * ollama pull bge-m3
 * mvn test -Dtest=AvaliacaoRagOllamaTest
 * </pre>
 * Falha se: (1) alguma resposta vazar historico de paciente fora do escopo (regra dura, vale para qualquer modelo) ou
 * (2) o Recall@4 por pergunta ficar abaixo de {@code -Drag.eval.recall.minimo} (padrao 0.70). Esse piso e um ponto de
 * partida: ajuste-o depois de ver os numeros reais do seu modelo.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",                                   // nao precisa do servidor MCP aqui
        "clinica.rag.docs-location=classpath:avaliacao-rag/docs/*.pdf",         // os mesmos PDFs do dataset
        "clinica.rag.top-k=8",                                                  // mede ate a 8a posicao
        "clinica.rag.similarity-threshold=0.0"                                  // sem corte: queremos ver as pontuacoes
})
@EnabledIf(value = "ollamaPronto", disabledReason = "Ollama indisponivel ou modelo de embeddings nao instalado "
        + "(veja a mensagem [avaliacao-rag] no console). Suba o Ollama e rode: ollama pull bge-m3")
class AvaliacaoRagOllamaTest {

    @Autowired
    ConsultorClinicoTools tools;

    @Autowired
    DocumentIngestionService ingestao;

    @Autowired
    JdbcClient jdbc;

    @Value("${spring.ai.ollama.embedding.options.model}")
    String modelo;

    @DynamicPropertySource
    static void bancoTemporario(DynamicPropertyRegistry registry) throws IOException {
        Path pasta = Files.createTempDirectory("avaliacao-rag");
        pasta.toFile().deleteOnExit();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + pasta.resolve("avaliacao.db") + "?foreign_keys=true");
    }

    @Test
    void avaliaARecuperacaoComOModeloAtual() {
        assertThat(jdbc.sql("SELECT nome FROM pacientes WHERE id = 104").query(String.class).optional())
                .as("o paciente 104 vem do data.sql").contains("Carlos Almeida");

        StatusIndexacao status = ingestao.getStatus();
        assertThat(status.estado())
                .as("A indexacao dos PDFs de avaliacao falhou (%s). O Ollama respondeu em /api/tags, mas o embedding falhou: "
                        + "confira o log do Ollama.", status)
                .isEqualTo(StatusIndexacao.Estado.PRONTA);
        assertThat(status.arquivos()).as("PDFs indexados").isEqualTo(2);
        assertThat(status.trechos()).as("trechos indexados").isPositive();

        Relatorio relatorio = AvaliadorRag.avaliar(modelo, 8, PerguntaRag.carregar(),
                p -> tools.consultarBaseClinica(p.pergunta(), p.pacienteId()));

        Path arquivo = Path.of("target/avaliacao-rag/relatorio-" + modelo.replaceAll("[^a-zA-Z0-9._-]", "_") + ".md");
        AvaliadorRag.gravar(relatorio, arquivo);
        System.out.println("\n" + relatorio.paraMarkdown());
        System.out.println("[avaliacao-rag] relatorio salvo em " + arquivo.toAbsolutePath());

        assertThat(relatorio.violacoesDeEscopo())
                .as("VAZAMENTO DE PRIVACIDADE: historico de paciente devolvido fora do escopo").isEmpty();

        double minimo = Double.parseDouble(System.getProperty("rag.eval.recall.minimo", "0.70"));
        assertThat(relatorio.recallPorPergunta(AvaliadorRag.K_PADRAO_DO_SERVIDOR))
                .as("Recall@%d por pergunta com o modelo %s (veja %s). Ajuste o piso com -Drag.eval.recall.minimo=...",
                        AvaliadorRag.K_PADRAO_DO_SERVIDOR, modelo, arquivo)
                .isGreaterThanOrEqualTo(minimo);
    }

    /**
     * Condicao do teste: o Ollama responde e o modelo configurado esta instalado. A URL e o modelo vem de
     * {@code application.properties} (fonte unica), podendo ser sobrescritos com {@code -Dspring.ai.ollama.base-url=...}
     * e {@code -Dspring.ai.ollama.embedding.options.model=...}.
     */
    static boolean ollamaPronto() {
        Properties aplicacao = propriedadesDaAplicacao();
        String url = System.getProperty("spring.ai.ollama.base-url",
                aplicacao.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
        String modelo = System.getProperty("spring.ai.ollama.embedding.options.model",
                aplicacao.getProperty("spring.ai.ollama.embedding.options.model", "bge-m3"));
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(url + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() == 200 && resposta.body().contains(modelo)) {
                return true;
            }
            System.out.println("[avaliacao-rag] O Ollama respondeu em " + url + ", mas o modelo '" + modelo
                    + "' nao esta instalado. Rode: ollama pull " + modelo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            System.out.println("[avaliacao-rag] Ollama inacessivel em " + url + " (" + e.getClass().getSimpleName()
                    + "). Suba o Ollama para rodar esta avaliacao.");
        }
        return false;
    }

    private static Properties propriedadesDaAplicacao() {
        Properties propriedades = new Properties();
        try (InputStream in = AvaliacaoRagOllamaTest.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                propriedades.load(in);
            }
        } catch (IOException e) {
            // sem o arquivo, valem os padroes abaixo
        }
        return propriedades;
    }
}
