import pyautogui, time
import numpy as np
from PIL import ImageGrab, Image, ImageEnhance, ImageOps, ImageFilter
from functools import partial
from datetime import time as dt_time, datetime, timedelta
import pytesseract
import subprocess
import requests
import json
from lognormal_from_list import calcular_parametros_lognormal

pytesseract.pytesseract.tesseract_cmd = '/usr/bin/tesseract' #default path
requests.packages.urllib3.disable_warnings(requests.packages.urllib3.exceptions.InsecureRequestWarning)

def is_automation_active(server_url="https://YOUR-SERVER-ADDRESS:5000/automation_flag2"):
    try:
        resp = requests.get(server_url, verify=False)
        if resp.status_code == 200:
            data = resp.json()
            return data.get("automation_active", False)
        else:
            print(f"Erro ao consultar flag: {resp.status_code}")
            return False
    except Exception as e:
        print("Erro na consulta:", e)
        return False
    

def sendCaptchaToSolve():
    # Comando bash como string
    comando = 'curl -k -F "image=@captchashot.png" https://YOUR-SERVER-ADDRESS:5000/upload'
    # Executa o comando
    resultado = subprocess.run(comando, shell=True, capture_output=True, text=True)
    # Mostra a saída do comando
    print("Saída:", resultado.stdout)
    print("Erros:", resultado.stderr)

    try:
        data = json.loads(resultado.stdout)
        job_id = data["job_id"]
        print("Job ID recebido:", job_id)
    except Exception as e:
        print("Erro ao processar a resposta:", e)
    

    while True:
        resp = requests.get(f"https://YOUR-SERVER-ADDRESS:5000/result/{job_id}", verify=False)
        data = resp.json()
        if data["status"] == "done":
            print("Captcha resolvido:", data["captcha_result"])
            break
        else:
            print("Aguardando resolução do captcha...")
            time.sleep(2)  # espera 2 segundos antes de tentar de novo

    return data["captcha_result"]


def searchCaptcha():
    print("Procurando por captcha...")
    # Localize pedidos de captcha
    try:
        captcha = pyautogui.locateOnScreen('captchareference.png', confidence=0.8, grayscale=True)
        if captcha:
            print("Captcha encontrado")
            left, top, width, height = captcha
            region_captcha = ((int)(left + 99), (int)(top + 77), 316, 60)
            captchashot = pyautogui.screenshot(region=region_captcha)
            captchashot.save('captchashot.png')
            # ENVIA CAPTCHA PARA RESOLUÇÃO:
            solution = sendCaptchaToSolve()
            pyautogui.moveTo((int)(left + 500), (int)(top + 100), duration = 1)
            pyautogui.click(button='left')
            pyautogui.write(solution.lower(), interval=0.1)
            pyautogui.press('enter')
            return True
    except Exception as e:
        print (f'Não existe captcha por resolver: {e}')
        return False


def horario_com_variacao(horario_base, variacao_min=10, variacao_max=30):
    """
    Retorna um horário datetime com variação aleatória entre -variacao_min e +variacao_max minutos.
    """
    agora = datetime.now()
    base_dt = agora.replace(hour=horario_base.hour, minute=horario_base.minute, second=0, microsecond=0)
    delta = timedelta(minutes=np.random.randint(-variacao_min, variacao_max))
    return base_dt + delta

def gerar_periodos():
    # Horários base (ajuste conforme sua rotina)
    base_pequeno_almoco_inicio = dt_time(7, 30)
    base_pequeno_almoco_fim    = dt_time(8, 0)
    base_almoco_inicio         = dt_time(12, 30)
    base_almoco_fim            = dt_time(13, 30)
    base_jantar_inicio         = dt_time(19, 30)
    base_jantar_fim            = dt_time(20, 30)
    base_sono_inicio           = dt_time(23, 30)
    base_sono_fim              = dt_time(7, 0)

    # Pequeno-almoço
    pa_inicio = horario_com_variacao(base_pequeno_almoco_inicio, 10, 15)
    pa_fim    = pa_inicio + timedelta(minutes=np.random.randint(15, 30))

    # Almoço
    almoco_inicio = horario_com_variacao(base_almoco_inicio, 10, 15)
    almoco_fim    = almoco_inicio + timedelta(minutes=np.random.randint(30, 60))

    # Jantar
    jantar_inicio = horario_com_variacao(base_jantar_inicio, 10, 15)
    jantar_fim    = jantar_inicio + timedelta(minutes=np.random.randint(30, 60))

    # Sono (considerando que o fim é no dia seguinte)
    sono_inicio = horario_com_variacao(base_sono_inicio, 10, 30)
    sono_fim    = horario_com_variacao(base_sono_fim, 10, 30) + timedelta(days=1)

    return {
        "Pequeno-almoço": (pa_inicio, pa_fim),
        "Almoço": (almoco_inicio, almoco_fim),
        "Jantar": (jantar_inicio, jantar_fim),
        "Sono": (sono_inicio, sono_fim)
    }


def verifica_e_atualiza_periodos():
    global periodos, data_periodos
    now = datetime.now()
    hoje = datetime.now().date()
    if (hoje != data_periodos) and (now.time() > dt_time(12,0)):
        periodos = gerar_periodos()
        data_periodos = periodos["Sono"][0].date()

    


############################################## BEGIN ###############################################

# Inicialização global
# periodos = gerar_periodos()
# data_periodos = periodos["Sono"][0].date()  # Data do início do primeiro período

# PRINT DOS PERÍODOS GERADOS
# print("\n--- Períodos gerados ---")
# for nome, (inicio, fim) in periodos.items():
#    print(f"{nome}: {inicio.strftime('%Y-%m-%d %H:%M')} - {fim.strftime('%Y-%m-%d %H:%M')}")
# print("------------------------\n")


forcestop = False # Não realiza mais cliques enquanto tiver dentro de um dos periodos principais
automation_active = True # Flag caso os captchas não possam ser resolvidos

# Lê o ficheiro e obtém os parâmetros média e desvio logarítmico
media_log, desvio_log = calcular_parametros_lognormal('sample.txt')

print(f'Média logarítmica: {media_log}')
print(f'Desvio padrão logarítmico: {desvio_log}')

time.sleep(5) # Delay de 5 segundos para abrir a janela onde o script deverá iniciar


while True:
    
#    verifica_e_atualiza_periodos()
#    agora = datetime.now()
    captcha = True # Leva à verificação da existencia de captcha

#    if periodos["Sono"][0] <= agora <= periodos["Sono"][1]:
#        print("Período de sono!")
#        numero = "2.896"
#    else:
#        print("Fora dos períodos principais.")
    numero = "115"
#        forcestop = False
    
    # Força a paragem se o cliente em Android não estiver preparado para resolver captchas que possam surgir
    automation_active = is_automation_active()

    # Apenas clica se a ordem não for de STOP
    if not forcestop and automation_active:
        try:
            # Localiza todos os botões de captura no ecrã
            botoes = list(pyautogui.locateAllOnScreen('button.png', confidence=0.8))
            for botao in botoes:
                left, top, width, height = botao
                # Região à esquerda do botão para capturar o texto
                region_text = ((int)(left - 100), (int)(top), 100, (int)(height))  # -100px para apanhar numero à esquerda do botão
                img = pyautogui.screenshot(region=region_text)
                img = img.convert('L')
                img = ImageOps.autocontrast(img, cutoff=2)
                img = img.resize((img.width * 2, img.height * 2), Image.BICUBIC)
                img = img.filter(ImageFilter.SHARPEN)
                enhancer = ImageEnhance.Sharpness(img)
                img = enhancer.enhance(2.5)
                texto = pytesseract.image_to_string(img, config='--psm 6').lower()
                print(f'Texto à esquerda: {texto}')
                if numero in texto:
                    # Gera coordenadas aleatórias dentro do retângulo do botão
                    x = np.random.randint(left, left + width - 1)
                    y = np.random.randint(top, top + height - 1)
                    pyautogui.moveTo(x, y, duration=np.random.uniform(0.7, 1.5))
                    pyautogui.click()
                    break
            # Após carregar no botão ver se algum captcha é pedido
            while captcha:
                time.sleep(2) # necessário caso o browser leve tempo a apresentar o captcha após o click
                captcha = searchCaptcha() # se captcha for pedido esta mesma chamada o resolve

        except Exception as e:
            print(f'Ocorreu um erro: {e}')
            print("Verificar posicionamento da janela")
        
        if numero != "115":
            forcestop = True
            

    # Ciclo concluido com sucesso, aguarda para nova chamada
    # Gera o próximo valor de tempo segundo a distribuição lognormal
    novo_tempo = True
    while(novo_tempo):
        proximo_tempo = int(np.random.lognormal(mean=media_log, sigma=desvio_log))
        if(proximo_tempo > 154000):
            print(f'A aguardar {proximo_tempo/1000} segundos')
            novo_tempo = False
    time.sleep(proximo_tempo/1000)




