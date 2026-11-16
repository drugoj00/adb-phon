"""Заменяет страницу, которую движок входа отдаёт браузеру после возврата с кодом, на нашу: русский язык, viewport, крупный текст по центру,
кнопка возврата в ADB-phon. Строка в бинарнике одна (шаблон Go html/template), поэтому длина новой строки должна быть РОВНО такой же:
короткий шаблон добивается пробелами. Работает над app/src/main/jniLibs/arm64-v8a/libclouds.so (уже с прежней правкой текста «Готово. Вернись в ADB-phon»
или свежим после tools/fetch_rclone.py). Запуск: python tools/patch_login_page.py [путь к libclouds.so]"""
import os, re, sys

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'jniLibs', 'arm64-v8a', 'libclouds.so')
b = open(path, 'rb').read()

start_marker = b'<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<title>{{ if .OK }}Success!'
s = b.find(start_marker)
if s < 0:
    # уже заменён нашим шаблоном: ищем по метке
    s = b.find(b'<!DOCTYPE html><html lang="ru">')
    if s < 0: sys.exit('шаблон страницы не найден')
# конец: после «</html>» идёт либо перевод строки (оригинал), либо пробелы добивки; граница строки: по длине оригинала
orig_old_tail = ('{{ else }}\nAll done. Please go back to rclone.\n{{ end }}\n</pre>\n</body>\n</html>\n').encode()
ours_tail = '{{ else }}Готово. Вернись в ADB-phon{{ end }}</pre></body></html>'.encode()
# длина всего шаблона известна из оригинала: от метки начала до конца старого хвоста
orig_head_len = None
if b.find(start_marker) >= 0:
    # оригинальный или с прежней правкой хвоста
    t0 = b.find(b'{{ else }}', s + 400)
    e = t0 + len(orig_old_tail)
else:
    sys.exit('повторный запуск по уже заменённому шаблону не поддерживается: возьмите свежий libclouds.so (tools/fetch_rclone.py)')
L = e - s
body_ru = ('<!DOCTYPE html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
           '<body style="font:20px sans-serif;text-align:center;margin:18vh 16px 0">{{ if .OK }}<h1>Готово</h1><p>Вернись в ADB-phon</p>'
           '<a href="intent://open#Intent;scheme=adbphon;package=app.adbphon;end" style="color:#0f766e">ADB-phon</a>'
           '{{ else }}<h1>Ошибка</h1><p>{{ .Name }}</p>{{ end }}</body></html>').encode()
if len(body_ru) > L:
    sys.exit(f'новая страница длиннее ({len(body_ru)} > {L}); сократите стиль')
new = body_ru + b' ' * (L - len(body_ru))
assert len(new) == L
b2 = b[:s] + new + b[e:]
assert len(b2) == len(b)
open(path, 'wb').write(b2)
print('OK, область', L, 'байт, страница', len(body_ru), 'байт, запас', L - len(body_ru))
