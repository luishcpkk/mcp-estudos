CREATE TABLE IF NOT EXISTS dentistas (
    id            INTEGER PRIMARY KEY,
    nome          TEXT NOT NULL,
    especialidade TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pacientes (
    id       INTEGER PRIMARY KEY,
    nome     TEXT NOT NULL,
    telefone TEXT
);

-- Consultas nunca sao apagadas: o cancelamento muda o status e guarda quando e por que.
-- Bancos criados antes das colunas motivo_cancelamento/cancelada_em sao ajustados
-- na inicializacao por InicializacaoBanco (CREATE TABLE IF NOT EXISTS nao altera tabela existente).
CREATE TABLE IF NOT EXISTS consultas (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    dentista_id         INTEGER NOT NULL REFERENCES dentistas(id),
    paciente_id         INTEGER NOT NULL REFERENCES pacientes(id),
    data                TEXT NOT NULL,
    hora                TEXT NOT NULL,
    procedimento        TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'AGENDADA',
    motivo_cancelamento TEXT,
    cancelada_em        TEXT
);

-- Impede dois agendamentos ativos no mesmo dentista, dia e hora.
-- Consultas CANCELADA ficam fora do indice, entao o horario volta a ficar livre.
CREATE UNIQUE INDEX IF NOT EXISTS ux_consulta_ativa
    ON consultas (dentista_id, data, hora) WHERE status = 'AGENDADA';

-- Propostas de agendamento: prepararAgendamento grava aqui, confirmarAgendamento consome.
-- O codigo vale uma vez (status PENDENTE -> CONFIRMADA) e expira em expira_em. Mantem o historico do que foi
-- proposto e do que foi confirmado. CREATE TABLE IF NOT EXISTS: bancos antigos ganham a tabela na proxima partida.
CREATE TABLE IF NOT EXISTS propostas_agendamento (
    codigo       TEXT PRIMARY KEY,
    dentista_id  INTEGER NOT NULL REFERENCES dentistas(id),
    paciente_id  INTEGER NOT NULL REFERENCES pacientes(id),
    data         TEXT NOT NULL,
    hora         TEXT NOT NULL,
    procedimento TEXT NOT NULL,
    criada_em    TEXT NOT NULL,
    expira_em    TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PENDENTE',
    consulta_id  INTEGER REFERENCES consultas(id)
);
