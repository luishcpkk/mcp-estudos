INSERT OR IGNORE INTO dentistas (id, nome, especialidade) VALUES
    (1, 'Dra. Ana Souza', 'Clínica Geral'),
    (2, 'Dr. Bruno Lima', 'Ortodontia'),
    (3, 'Dra. Carla Mendes', 'Endodontia');

INSERT OR IGNORE INTO pacientes (id, nome, telefone) VALUES
    (1, 'Maria Oliveira', '(31) 90000-0001'),
    (2, 'João Pereira', '(31) 90000-0002'),
    (3, 'Maria Fernanda Costa', '(31) 90000-0003'),
    (104, 'Carlos Almeida', '(31) 90000-0104');

-- O paciente 104 (Carlos Almeida, ficticio) e o dono do historico de exemplo paciente-104-*.pdf da base clinica.
-- Como todo o arquivo, usa OR IGNORE: ele roda a cada partida e nao pode falhar se o paciente ja existir.

-- A consulta de exemplo nao e mais criada aqui: date('now') do SQLite e UTC e erra o dia
-- no fuso de Sao Paulo. Ela e gerada em Java (InicializacaoBanco) a partir do Clock.
