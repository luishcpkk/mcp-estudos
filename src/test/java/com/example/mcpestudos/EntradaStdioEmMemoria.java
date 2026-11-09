package com.example.mcpestudos;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * "stdin" do servidor MCP nos testes: o teste enfileira texto com {@link #enviar(String)} e o
 * {@code read()} do transporte bloqueia ate haver dado ou ate {@link #encerrar()}.
 */
final class EntradaStdioEmMemoria extends InputStream {

    private static final byte[] FIM = new byte[0];

    private final BlockingQueue<byte[]> fila = new LinkedBlockingQueue<>();
    private byte[] atual = new byte[0];
    private int posicao;
    private boolean encerrada;

    void enviar(String texto) {
        fila.add(texto.getBytes(StandardCharsets.UTF_8));
    }

    void encerrar() {
        fila.add(FIM);
    }

    @Override
    public int read(byte[] destino, int inicio, int tamanho) throws IOException {
        if (tamanho == 0) {
            return 0;
        }
        try {
            while (posicao >= atual.length) {
                if (encerrada) {
                    return -1;
                }
                byte[] proximo = fila.take();
                if (proximo == FIM) {
                    encerrada = true;
                    return -1;
                }
                atual = proximo;
                posicao = 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
        int n = Math.min(tamanho, atual.length - posicao);
        System.arraycopy(atual, posicao, destino, inicio, n);
        posicao += n;
        return n;
    }

    @Override
    public int read() throws IOException {
        byte[] um = new byte[1];
        return read(um, 0, 1) == -1 ? -1 : um[0] & 0xff;
    }
}
