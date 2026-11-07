package com.example.mcpestudos;

/**
 * Trecho de um documento clinico devolvido pela busca.
 *
 * @param fonte       nome do arquivo PDF
 * @param pagina      pagina do PDF (1 e a primeira); {@code null} se desconhecida
 * @param similaridade proximidade com a duvida, de 0 a 1 (quanto maior, mais parecido)
 * @param texto       conteudo do trecho
 */
public record TrechoClinico(String fonte, Integer pagina, double similaridade, String texto) {
}
