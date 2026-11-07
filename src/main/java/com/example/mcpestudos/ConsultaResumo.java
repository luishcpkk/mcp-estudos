package com.example.mcpestudos;

/** Consulta agendada de um paciente. {@code data} em AAAA-MM-DD e {@code hora} em HH:mm. */
public record ConsultaResumo(long id, String data, String hora, String procedimento, String dentista) {
}
