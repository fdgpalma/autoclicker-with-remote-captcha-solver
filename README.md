# autoclicker-with-remote-captcha-solver
autoclicker-with-remote-captcha-solver is an advanced autoclicker simulating realistic human clicks, evading anti-bot detection. Uses lognormal distribution for click intervals (not uniform), capturing natural asymmetry: values clustered near mean (~150s) with long tail for rare delays (>200s), fitted via np.random.lognormal(m, s) from real data.​

Integrates remote CAPTCHA solver via Flask API (Python + pytesseract/OCR), Android app (Kotlin), and pyautogui for browser. Features Weibull/Gamma pauses for distractions and routine sleep (±15min/day). Ethical for automation/testing; MIT license.
