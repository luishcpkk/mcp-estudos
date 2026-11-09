package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Regressao do transporte stdio do SDK do MCP: chamadas que terminam ao mesmo tempo nao podem perder
 * resposta nem derrubar o servidor.
 *
 * <p>No SDK 2.0.0 o transporte enfileirava cada resposta com {@code Sinks.Many.tryEmitNext}, que nao aceita
 * emissao concorrente: uma das threads recebia {@code FAIL_NON_SERIALIZED}, a resposta era descartada
 * ({@code Failed to enqueue message}) e o servidor encerrava. O SDK 2.1.0 serializa a emissao. Este teste
 * usa o servidor MCP e o transporte stdio reais, so com streams em memoria no lugar de stdin/stdout, e
 * protege a versao do SDK que o Spring AI traz (a partir do Spring AI 2.0.2).
 */
class TransporteStdioConcorrenteTest {

    private static final int RAJADAS = 10;
    private static final int CHAMADAS_POR_RAJADA = 50;
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @Test
    @Timeout(180)
    void respondeATodasAsChamadasSimultaneasSemDerrubarOServidor() throws Exception {
        var entrada = new EntradaStdioEmMemoria();
        var saida = new ByteArrayOutputStream();
        var transporte = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), entrada, saida);
        Tool eco = Tool.builder().name("eco").description("responde ok")
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        McpSyncServer servidor = McpServer.sync(transporte).serverInfo("teste", "1")
                .toolCall(eco, (exchange, request) -> CallToolResult.builder().addTextContent("ok").build())
                .build();
        try {
            entrada.enviar("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"teste\",\"version\":\"1\"}}}\n");
            aguardar(() -> idsRespondidos(saida).contains(1), 20_000);
            entrada.enviar("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");

            int proximoId = 1000;
            for (int rajada = 0; rajada < RAJADAS; rajada++) {
                Set<Integer> pedidos = new TreeSet<>();
                StringBuilder lote = new StringBuilder();
                for (int i = 0; i < CHAMADAS_POR_RAJADA; i++) {
                    int id = proximoId++;
                    pedidos.add(id);
                    lote.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                            .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"eco\",\"arguments\":{}}}\n");
                }
                entrada.enviar(lote.toString()); // uma unica escrita: as 50 chegam juntas e rodam em paralelo

                aguardar(() -> idsRespondidos(saida).containsAll(pedidos), 15_000);
                Set<Integer> perdidas = new TreeSet<>(pedidos);
                perdidas.removeAll(idsRespondidos(saida));
                assertThat(perdidas)
                        .as("rajada %d: respostas perdidas (transporte stdio com emissao concorrente)", rajada)
                        .isEmpty();
            }
        } finally {
            servidor.closeGracefully();
            entrada.encerrar();
        }
    }

    private static Set<Integer> idsRespondidos(ByteArrayOutputStream saida) {
        Set<Integer> ids = new TreeSet<>();
        Matcher m = ID.matcher(saida.toString(StandardCharsets.UTF_8));
        while (m.find()) {
            ids.add(Integer.parseInt(m.group(1)));
        }
        return ids;
    }

    private static void aguardar(BooleanSupplier condicao, long limiteMs) throws InterruptedException {
        long fim = System.currentTimeMillis() + limiteMs;
        while (!condicao.getAsBoolean() && System.currentTimeMillis() < fim) {
            Thread.sleep(20);
        }
    }
}
