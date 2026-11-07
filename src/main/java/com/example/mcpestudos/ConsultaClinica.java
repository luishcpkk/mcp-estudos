package com.example.mcpestudos;

import java.util.List;

/**
 * Resposta de {@code consultarBaseClinica}.
 *
 * @param duvida     a duvida consultada, sem espacos nas pontas
 * @param pacienteId paciente cujo historico entrou na busca; {@code null} quando so protocolos foram consultados
 * @param trechos    trechos encontrados, do mais para o menos similar
 * @param aviso      orientacao de uso dos trechos
 */
public record ConsultaClinica(String duvida, Long pacienteId, List<TrechoClinico> trechos, String aviso) {
}
