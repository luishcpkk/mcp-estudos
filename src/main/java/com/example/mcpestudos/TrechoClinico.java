package com.example.mcpestudos;

/**
 * Trecho de um documento clinico devolvido pela busca.
 *
 * @param fonte        nome do arquivo PDF
 * @param pagina       pagina do PDF (1 e a primeira); {@code null} se desconhecida
 * @param similaridade proximidade com a duvida, de 0 a 1 (quanto maior, mais parecido)
 * @param escopo       {@code PROTOCOLO} (orientacao geral da clinica) ou {@code PACIENTE} (historico de um paciente)
 * @param pacienteId   de quem e o historico quando {@code escopo} e {@code PACIENTE}; {@code null} em protocolos
 * @param texto        conteudo do trecho
 */
public record TrechoClinico(String fonte, Integer pagina, double similaridade, String escopo, Long pacienteId,
                            String texto) {
}
