package com.example.mcpestudos;

import java.util.List;

/**
 * Resposta de {@code acessosAoHistorico}.
 *
 * @param pacienteId paciente consultado
 * @param total      quantos acessos existem no total para este paciente (pode ser maior que {@code acessos})
 * @param acessos    os mais recentes, do mais novo para o mais antigo
 * @param aviso      o que o registro mostra e o que ele nao mostra
 */
public record RegistroDeAcessos(long pacienteId, int total, List<AcessoAoHistorico> acessos, String aviso) {
}
