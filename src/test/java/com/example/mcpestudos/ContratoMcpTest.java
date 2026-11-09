package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de CONTRATO do servidor MCP: o que o Claude enxerga (nomes das ferramentas, parametros, o que e
 * obrigatorio, formato das respostas) nao pode mudar sem que alguem perceba.
 *
 * <p>Sobe o contexto Spring REAL da aplicacao (as mesmas propriedades, nome, instrucoes e ferramentas de
 * producao). A unica troca e o transporte: no lugar do stdio ligado ao {@code System.in/out}, que o Surefire
 * usa para o proprio protocolo, entra um transporte stdio do SDK com streams em memoria
 * ({@link ClienteMcpEmMemoria}). Todo o resto e o que o Claude Desktop conversa.
 *
 * <p><b>Quando este teste falha</b>, uma ferramenta foi adicionada, removida ou teve parametro alterado.
 * Se a mudanca e intencional, regenere o retrato e revise o diff no git:
 * <pre>mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true</pre>
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.stdio=false",                     // o transporte vem do bean de teste abaixo
        "clinica.rag.docs-location=file:/nao/existe/*.pdf"      // sem PDFs: nada a indexar, nao precisa de Ollama
})
class ContratoMcpTest {

    /** Retrato versionado do contrato. */
    private static final String RETRATO = "/contrato/ferramentas-mcp.json";
    /** Onde o retrato e regravado com -Dcontrato.atualizar=true (relativo a raiz do projeto). */
    private static final Path RETRATO_NO_PROJETO = Path.of("src/test/resources/contrato/ferramentas-mcp.json");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final ClienteMcpEmMemoria CLIENTE = new ClienteMcpEmMemoria();
    private static Map<String, Object> inicializacao;

    @TestConfiguration
    static class TransporteEmMemoria {
        @Bean
        StdioServerTransportProvider transporteStdioDeTeste() {
            return CLIENTE.transporte();
        }
    }

    @DynamicPropertySource
    static void bancoTemporario(DynamicPropertyRegistry registry) throws IOException {
        Path pasta = Files.createTempDirectory("contrato-mcp");
        pasta.toFile().deleteOnExit();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + pasta.resolve("contrato.db") + "?foreign_keys=true");
    }

    @BeforeEach
    void conectar() {
        if (inicializacao == null) {
            inicializacao = CLIENTE.iniciar();
        }
    }

    // ---------- o servidor ----------

    @Test
    void oServidorSeApresentaComONomeAVersaoEAsInstrucoesCertas() {
        Map<String, Object> servidor = ClienteMcpEmMemoria.mapa(inicializacao.get("serverInfo"));
        assertThat(servidor).containsEntry("name", "odonto-mcp").containsEntry("version", "0.0.1");
        // "odonto-mcp" e a chave que o Claude Desktop mostra: renomear muda o que o usuario ve.

        assertThat((String) inicializacao.get("instructions"))
                .as("instrucoes do servidor (orientam o modelo antes de qualquer chamada)")
                .contains("consultarBaseClinica")
                .contains("pacienteId")
                .contains("confirme com o usuario");
        assertThat(ClienteMcpEmMemoria.mapa(inicializacao.get("capabilities"))).containsKey("tools");
    }

    // ---------- o contrato das ferramentas ----------

    @Test
    void asFerramentasPublicadasBatemComORetratoVersionado() throws IOException {
        Map<String, Object> atual = contratoAtual();

        if (Boolean.getBoolean("contrato.atualizar")) {
            Files.createDirectories(RETRATO_NO_PROJETO.getParent());
            Files.writeString(RETRATO_NO_PROJETO, JSON.writeValueAsString(atual) + "\n", StandardCharsets.UTF_8);
            System.out.println("[contrato] retrato regravado em " + RETRATO_NO_PROJETO.toAbsolutePath()
                    + " (" + atual.size() + " ferramentas). Revise o diff no git antes de commitar.");
            return;
        }

        Map<String, Object> esperado = lerRetrato();
        if (esperado.equals(atual)) {
            return;
        }
        fail(explicarDiferencas(esperado, atual));
    }

    @Test
    void todaFerramentaETodoParametroTemDescricaoParaOModelo() {
        // A descricao e o "prompt" da ferramenta: sem ela o modelo nao sabe quando nem como chamar.
        for (Map<String, Object> ferramenta : CLIENTE.listarFerramentas()) {
            String nome = (String) ferramenta.get("name");
            assertThat((String) ferramenta.get("description"))
                    .as("descricao da ferramenta %s", nome).isNotBlank().hasSizeGreaterThan(40);
            Map<String, Object> propriedades = ClienteMcpEmMemoria.mapa(
                    ClienteMcpEmMemoria.mapa(ferramenta.get("inputSchema")).get("properties"));
            propriedades.forEach((parametro, definicao) -> assertThat((String) ClienteMcpEmMemoria.mapa(definicao).get("description"))
                    .as("descricao do parametro %s.%s", nome, parametro).isNotBlank());
        }
    }

    @Test
    void asDescricoesCarregamOsAvisosQueGuiamOComportamentoDoModelo() {
        Map<String, String> descricoes = new LinkedHashMap<>();
        CLIENTE.listarFerramentas().forEach(f -> descricoes.put((String) f.get("name"), (String) f.get("description")));

        assertThat(descricoes.get("agendarConsulta")).contains("confirmar");           // nao agendar sem confirmar
        assertThat(descricoes.get("cancelarConsulta")).contains("futuras");            // so cancela consulta futura
        assertThat(descricoes.get("consultarBaseClinica"))
                .contains("ANTES de agendar")                                          // consultar protocolo antes
                .contains("pacienteId")                                                // escopo por paciente
                .contains("em vez de supor");                                          // nao inventar protocolo
    }

    // ---------- o formato das respostas ----------

    @Test
    void asRespostasMantemOFormatoEsperadoPeloModelo() {
        Map<?, ?> hoje = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("dataDeHoje", Map.of());
        assertThat(chaves(hoje)).containsExactlyInAnyOrder("data", "diaDaSemana");
        assertThat((String) hoje.get("data")).matches("\\d{4}-\\d{2}-\\d{2}");

        List<?> dentistas = (List<?>) CLIENTE.chamarFerramentaEObterJson("listarDentistas", Map.of());
        assertThat(dentistas).isNotEmpty();
        assertThat(chaves(dentistas.get(0))).containsExactlyInAnyOrder("id", "nome", "especialidade");

        // Envelope Resultado: {sucesso, dados, erro}
        Map<?, ?> pacientes = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("buscarPaciente", Map.of("nome", "Maria"));
        assertThat(chaves(pacientes)).containsExactlyInAnyOrder("sucesso", "dados", "erro");
        assertThat(pacientes.get("sucesso")).isEqualTo(true);
        assertThat(chaves(((List<?>) pacientes.get("dados")).get(0)))
                .containsExactlyInAnyOrder("id", "nome", "telefone");

        Map<?, ?> recusa = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("buscarPaciente", Map.of("nome", "ab"));
        assertThat(recusa.get("sucesso")).isEqualTo(false);
        assertThat((String) recusa.get("erro")).contains("3 caracteres");

        Map<?, ?> dataInvalida = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("horariosLivres",
                Map.of("dentistaId", 1, "data", "nao-e-uma-data"));
        assertThat(dataInvalida.get("sucesso")).isEqualTo(false);
        assertThat((String) dataInvalida.get("erro")).contains("AAAA-MM-DD");

        Map<?, ?> duvidaCurta = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica", Map.of("duvida", "abc"));
        assertThat(duvidaCurta.get("sucesso")).isEqualTo(false);
        assertThat((String) duvidaCurta.get("erro")).contains("5 caracteres");
    }

    @Test
    void parametroObrigatorioAusenteViraErroDoProtocoloENaoChegaNaFerramenta() {
        // O SDK valida a entrada contra o schema antes de chamar o metodo: sem pacienteId, nem executa.
        Map<String, Object> resultado = CLIENTE.chamarFerramenta("consultasDoPaciente", Map.of());

        assertThat(resultado.get("isError")).isEqualTo(true);
        assertThat(resultado.toString()).contains("pacienteId");
    }

    private static Set<String> chaves(Object mapa) {
        Set<String> nomes = new TreeSet<>();
        ((Map<?, ?>) mapa).keySet().forEach(k -> nomes.add(String.valueOf(k)));
        return nomes;
    }

    // ---------- o retrato ----------

    /** O contrato como o cliente o ve, em forma canonica (chaves ordenadas, sem textos de descricao). */
    private static Map<String, Object> contratoAtual() {
        Map<String, Object> contrato = new TreeMap<>();
        for (Map<String, Object> ferramenta : CLIENTE.listarFerramentas()) {
            Map<String, Object> entrada = new TreeMap<>();
            entrada.put("inputSchema", canonico(ferramenta.get("inputSchema")));
            contrato.put((String) ferramenta.get("name"), entrada);
        }
        return contrato;
    }

    /**
     * Ordena as chaves, ordena as listas de campos obrigatorios e descarta os textos {@code description}:
     * o contrato e a ESTRUTURA (nomes, tipos, obrigatoriedade). Ajustar o texto de uma descricao e engenharia
     * de prompt, nao quebra de contrato; ele e vigiado a parte pelas regras de descricao acima.
     */
    private static Object canonico(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> ordenado = new TreeMap<>();
            mapa.forEach((chave, v) -> {
                String nome = String.valueOf(chave);
                if (!"description".equals(nome) || ehPropriedadeChamadaDescription(mapa)) {
                    ordenado.put(nome, canonico(v));
                }
            });
            return ordenado;
        }
        if (valor instanceof List<?> lista) {
            List<Object> itens = new ArrayList<>();
            lista.forEach(v -> itens.add(canonico(v)));
            if (itens.stream().allMatch(String.class::isInstance)) {
                itens.sort(null);
            }
            return itens;
        }
        return valor;
    }

    /** Um parametro de nome "description" dentro de {@code properties} nao e o texto de descricao. */
    private static boolean ehPropriedadeChamadaDescription(Map<?, ?> mapa) {
        Object descricao = mapa.get("description");
        return descricao instanceof Map<?, ?>;
    }

    private static Map<String, Object> lerRetrato() throws IOException {
        try (InputStream in = ContratoMcpTest.class.getResourceAsStream(RETRATO)) {
            if (in == null) {
                fail("Retrato do contrato nao encontrado em " + RETRATO + ". Gere-o uma vez com: "
                        + "mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true");
            }
            return ClienteMcpEmMemoria.mapa(JSON.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Object.class));
        }
    }

    private static String explicarDiferencas(Map<String, Object> esperado, Map<String, Object> atual) {
        TreeSet<String> removidas = new TreeSet<>(esperado.keySet());
        removidas.removeAll(atual.keySet());
        TreeSet<String> adicionadas = new TreeSet<>(atual.keySet());
        adicionadas.removeAll(esperado.keySet());

        StringBuilder msg = new StringBuilder("\nO CONTRATO DAS FERRAMENTAS MCP MUDOU.\n");
        if (!removidas.isEmpty()) {
            msg.append("\n- Ferramentas REMOVIDAS ou RENOMEADAS (o Claude deixa de encontra-las): ").append(removidas);
        }
        if (!adicionadas.isEmpty()) {
            msg.append("\n- Ferramentas NOVAS: ").append(adicionadas);
        }
        for (String nome : esperado.keySet()) {
            if (atual.containsKey(nome) && !esperado.get(nome).equals(atual.get(nome))) {
                msg.append("\n- Parametros ALTERADOS em ").append(nome).append(":\n    esperado: ")
                        .append(JSON.writeValueAsString(esperado.get(nome)).replaceAll("\\s+", " "))
                        .append("\n    atual:    ")
                        .append(JSON.writeValueAsString(atual.get(nome)).replaceAll("\\s+", " "));
            }
        }
        msg.append("\n\nSe a mudanca e intencional, regenere o retrato e revise o diff no git:\n")
                .append("    mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true\n");
        return msg.toString();
    }
}
