import numpy as np

def calcular_parametros_lognormal(nome_ficheiro):
    # Lê valores positivos do ficheiro (um por linha)
    with open(nome_ficheiro, 'r') as f:
        valores = []
        for linha in f:
            try:
                v = float(linha.strip())
                if v > 0:
                    valores.append(v)
            except ValueError:
                continue  # Ignorar linhas não numéricas

    amostra = np.array(valores)
    if len(amostra) == 0:
        raise ValueError("O ficheiro não contém valores válidos positivos.")

    # Cálculo logarítmico
    log_amostra = np.log(amostra)
    media_log = np.mean(log_amostra)
    desvio_log = np.std(log_amostra, ddof=1)

    return media_log, desvio_log