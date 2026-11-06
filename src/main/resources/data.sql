INSERT OR IGNORE INTO dentistas (id, nome, especialidade) VALUES
    (1, 'Dra. Ana Souza', 'Clínica Geral'),
    (2, 'Dr. Bruno Lima', 'Ortodontia'),
    (3, 'Dra. Carla Mendes', 'Endodontia');

INSERT OR IGNORE INTO pacientes (id, nome, telefone) VALUES
    (1, 'Maria Oliveira', '(31) 90000-0001'),
    (2, 'João Pereira', '(31) 90000-0002'),
    (3, 'Maria Fernanda Costa', '(31) 90000-0003');

INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento)
SELECT 1, 1, date('now', '+1 day'), '09:00', 'Limpeza'
WHERE NOT EXISTS (SELECT 1 FROM consultas);