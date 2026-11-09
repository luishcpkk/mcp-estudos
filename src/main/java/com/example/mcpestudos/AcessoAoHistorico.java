package com.example.mcpestudos;

/**
 * Uma linha do registro de acessos ao historico de um paciente. Nao tem a pergunta nem o conteudo devolvido: so o fato.
 *
 * @param id          numero sequencial; quanto maior, mais recente
 * @param pacienteId  paciente cujo historico foi acessado
 * @param ferramenta  ferramenta que acessou ({@code consultasDoPaciente} ou {@code consultarBaseClinica})
 * @param acessadoEm  instante do acesso, com o deslocamento do fuso da clinica (ex.: {@code 2026-11-09T10:30:15-03:00})
 * @param trechos     quantos itens foram devolvidos (consultas, ou trechos de documentos do paciente); pode ser 0
 */
public record AcessoAoHistorico(long id, long pacienteId, String ferramenta, String acessadoEm, int trechos) {
}
