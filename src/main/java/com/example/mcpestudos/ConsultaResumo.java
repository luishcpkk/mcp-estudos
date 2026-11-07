package com.example.mcpestudos;

/**
 * Consulta de um paciente. {@code data} em AAAA-MM-DD e {@code hora} em HH:mm.
 * {@code status} e AGENDADA (futura) ou REALIZADA (ja passou, pelo Clock da clinica).
 */
public record ConsultaResumo(long id, String data, String hora, String procedimento, String dentista,
                             String status) {
}
