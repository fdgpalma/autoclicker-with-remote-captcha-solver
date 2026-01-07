from flask import Flask, request, send_file, jsonify
import uuid
from mimetypes import guess_type
import os
import time
import threading

app = Flask(__name__)

UPLOAD_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'uploads')
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# Simples banco de dados em memória
jobs = {}
jobs_lock = threading.Lock()

# Controla o estado da automação nos clientes
# Este estado é controlado a partir da aplicação Android
automation_active = False

# Rotina a cada timeout para situação de fallback na resolução dos captchas
# Mesmo que no Android a flag esteja como disponivel para resolver captchas, caso não resolva em timeout, a variavel irá para False
def verificar_jobs_pendentes(timeout=120):
    global automation_active
    while automation_active:
        time.sleep(timeout)  # Espera 120 segundos entre as verificações
        tempo_atual = time.time()
        
        with jobs_lock:
            for job in jobs:
                if job.get("status") == "waiting_captcha":
                    timestamp = job.get("timestamp", 0)
                    if timestamp > 0 and (tempo_atual - timestamp) > timeout:
                        automation_active = False
                        print(f"Automação desativada: job '{job.get('id')}' pendente há mais de {timeout} segundos.")
                        break



# Servidor Flask recebe do cliente os captchas
# Guarda as imagens e atribui IDs aos jobs
@app.route('/upload', methods=['POST'])
def upload_image():
    if 'image' not in request.files:
        return jsonify({'error': 'No image part'}), 400
    file = request.files['image']
    if file.filename == '':
        return jsonify({'error': 'No selected file'}), 400
    job_id = str(uuid.uuid4())
    filepath = os.path.join(UPLOAD_FOLDER, f"{job_id}_{file.filename}")
    file.save(filepath)
    jobs[job_id] = {"status": "waiting_captcha", "filepath": filepath, "captcha_result": None, "timestamp": time.time()}
    return jsonify({'job_id': job_id}), 200


# Aplicação android entrega a solução
@app.route('/captcha_result/<job_id>', methods=['POST'])
def captcha_result(job_id):
    data = request.get_json()
    captcha = data.get("captcha")
    if job_id not in jobs:
        return jsonify({"error": "Job not found"}), 404
    jobs[job_id]["captcha_result"] = captcha
    jobs[job_id]["status"] = "done"
    return jsonify({"status": "received"}), 200


# Entrega a solução ao cliente
# Ou retorna 'em espera' caso não exista solução
@app.route('/result/<job_id>', methods=['GET'])
def get_result(job_id):
    job = jobs.get(job_id)
    if not job:
        return jsonify({"error": "Job not found"}), 404
    if job["captcha_result"] is None:
        return jsonify({"status": "waiting"}), 200
    return jsonify({"status": "done", "captcha_result": job["captcha_result"]}), 200


# Aplicação android recebe a lista de pendentes
@app.route('/pending_jobs', methods=['GET'])
def pending_jobs():
    pending = []
    for job_id, job in jobs.items():
        if job['status'] == 'waiting_captcha':
            pending.append({
                'job_id': job_id,
                'image_url': f"/image/{os.path.basename(job['filepath'])}"
            })
    return jsonify(pending), 200


# Recebe do Android o estado da automação
@app.route('/automation_flag', methods=['POST'])
def set_automation_flag():
    global automation_active
    data = request.get_json()
    automation_active = data.get('automation_active', False)
    return {"status": "ok", "automation_active": automation_active}


# Entrega o estado da automação ao cliente
@app.route('/automation_flag', methods=['GET'])
def get_automation_flag():
    return {"automation_active": automation_active}


# Entrega a imagem do captcha ao cliente Android
@app.route('/image/<filename>', methods=['GET'])
def get_image(filename):
    filepath = os.path.join(UPLOAD_FOLDER, filename)
    print("A procurar ficheiro em:", filepath)
    if not os.path.exists(filepath):
        return jsonify({'error': 'File not found'}), 404
    mimetype, _ = guess_type(filepath)
    return send_file(filepath, mimetype=mimetype)

if __name__ == '__main__':
    monitor_thread = threading.Thread(target=verificar_jobs_pendentes, daemon=True)
    monitor_thread.start()
    app.run(debug=True, port=5000, host='0.0.0.0', ssl_context=('server.crt', 'server.key'))