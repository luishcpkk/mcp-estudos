package com.example.mcpestudos;

import java.util.List;

/** Horarios livres de um dentista em uma data. {@code data} em AAAA-MM-DD, horarios em HH:mm. */
public record HorariosLivres(long dentistaId, String data, List<String> horariosLivres) {
}
