package com.example.mcpestudos;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente MCP minimo para testes: fala JSON-RPC com o servidor pelo transporte stdio REAL do SDK, mas
 * com streams em memoria no lugar de {@code System.in/out} (que o Surefire usa para o proprio protocolo).
 *
 * <p>Uso: crie uma instancia, entregue {@link #transporte()} ao servidor e converse com
 * {@link #requisitar} / {@link #chamarFerramenta}.
 */
final class ClienteMcpEmMemoria {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long LIMITE_MS = 90_000;

    private final EntradaStdioEmMemoria entrada = new EntradaStdioEmMemoria();
    private final ByteArrayOutputStream saida = new ByteArrayOutputStream();
    private final AtomicInteger proximoId = new AtomicInteger(1);
    private boolean iniciado;

    /** Transporte stdio do SDK ligado aos streams em memoria deste cliente. */
    StdioServerTransportProvider transporte() {
        return new StdioServerTransportProvider(McpJsonDefaults.getMapper(), entrada, saida);
    }

    /** Faz o handshake ({@code initialize} + {@code initialized}) uma unica vez e devolve o {@code result}. */
    synchronized Map<String, Object> iniciar() {
        Map<String, Object> resposta = requisitar("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "teste-de-contrato", "version", "1")));
        if (!iniciado) {
            entrada.enviar("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            iniciado = true;
        }
        return mapa(resposta.get("result"));
    }

    /** Envia uma requisicao JSON-RPC e espera a resposta com o mesmo id. Devolve a mensagem inteira. */
    Map<String, Object> requisitar(String metodo, Map<String, Object> parametros) {
        int id = proximoId.getAndIncrement();
        Map<String, Object> mensagem = new LinkedHashMap<>();
        mensagem.put("jsonrpc", "2.0");
        mensagem.put("id", id);
        mensagem.put("method", metodo);
        mensagem.put("params", parametros);
        entrada.enviar(JSON.writeValueAsString(mensagem) + "\n");
        return aguardarResposta(id);
    }

    /** {@code tools/call}: devolve o {@code result} do protocolo ({@code content}, {@code isError}...). */
    Map<String, Object> chamarFerramenta(String nome, Map<String, Object> argumentos) {
        Map<String, Object> resposta = requisitar("tools/call", Map.of("name", nome, "arguments", argumentos));
        return mapa(resposta.get("result"));
    }

    /** O texto da ferramenta ja interpretado como JSON (objeto vira Map, lista vira List). */
    Object chamarFerramentaEObterJson(String nome, Map<String, Object> argumentos) {
        Map<String, Object> resultado = chamarFerramenta(nome, argumentos);
        List<?> conteudo = (List<?>) resultado.get("content");
        String texto = (String) mapa(conteudo.get(0)).get("text");
        return JSON.readValue(texto, Object.class);
    }

    /** As ferramentas publicadas pelo servidor ({@code tools/list}), como o cliente as ve. */
    List<Map<String, Object>> listarFerramentas() {
        Map<String, Object> resposta = requisitar("tools/list", Map.of());
        List<?> brutas = (List<?>) mapa(resposta.get("result")).get("tools");
        return brutas.stream().map(ClienteMcpEmMemoria::mapa).toList();
    }

    void encerrar() {
        entrada.encerrar();
    }

    private Map<String, Object> aguardarResposta(int id) {
        long fim = System.currentTimeMillis() + LIMITE_MS;
        while (System.currentTimeMillis() < fim) {
            String texto = saida.toString(StandardCharsets.UTF_8);
            int ultimaQuebra = texto.lastIndexOf('\n');
            if (ultimaQuebra >= 0) {
                for (String linha : texto.substring(0, ultimaQuebra).split("\n")) {
                    if (linha.isBlank()) {
                        continue;
                    }
                    Map<String, Object> mensagem = mapa(JSON.readValue(linha, Object.class));
                    if (mensagem.get("id") instanceof Number n && n.intValue() == id) {
                        return mensagem;
                    }
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrompido aguardando a resposta " + id, e);
            }
        }
        throw new IllegalStateException("O servidor MCP nao respondeu a requisicao " + id + " em "
                + LIMITE_MS / 1000 + "s. Saida recebida ate agora: " + saida.toString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapa(Object valor) {
        return (Map<String, Object>) valor;
    }
}
