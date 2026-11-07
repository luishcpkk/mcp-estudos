package com.example.mcpestudos;

/**
 * Envelope de resposta das ferramentas que podem recusar um pedido por regra de negocio
 * (data invalida, horario ocupado...). Falhas tecnicas nao passam por aqui: propagam como excecao.
 *
 * @param sucesso {@code true} quando o pedido foi atendido
 * @param dados   conteudo da resposta quando {@code sucesso}; {@code null} caso contrario
 * @param erro    motivo da recusa quando {@code !sucesso}, escrito para o usuario final; {@code null} caso contrario
 */
public record Resultado<T>(boolean sucesso, T dados, String erro) {

    public static <T> Resultado<T> ok(T dados) {
        return new Resultado<>(true, dados, null);
    }

    public static <T> Resultado<T> falha(String erro) {
        return new Resultado<>(false, null, erro);
    }
}
