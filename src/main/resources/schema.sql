CREATE TABLE IF NOT EXISTS dentistas (
                                         id INTEGER PRIMARY KEY,
                                         nome TEXT NOT NULL,
                                         especialidade TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pacientes (
                                         id INTEGER PRIMARY KEY,
                                         nome TEXT NOT NULL,
                                         telefone TEXT
);

CREATE TABLE IF NOT EXISTS consultas (
                                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                                         dentista_id INTEGER NOT NULL REFERENCES dentistas(id),
    paciente_id INTEGER NOT NULL REFERENCES pacientes(id),
    data TEXT NOT NULL,
    hora TEXT NOT NULL,
    procedimento TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'AGENDADA'
    );

-- Impede dois agendamentos ativos no mesmo dentista, dia e hora
CREATE UNIQUE INDEX IF NOT EXISTS ux_consulta_ativa
    ON consultas (dentista_id, data, hora) WHERE status = 'AGENDADA';