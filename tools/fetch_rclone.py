"""Скачивает официальный Linux-arm64 бинарник облачного движка (rclone), сверяет SHA-256 с SHA256SUMS с downloads.rclone.org,
подменяет текст страницы «всё готово» после входа в браузере (то же число байт) и кладёт результат в
clouds/src/<вариант>/jniLibs/<abi>/libclouds.so (запускается из каталога нативных библиотек модуля «ADB-phon Облака»).
Варианты модуля: arm64 (arm64-v8a), arm32 (armeabi-v7a), x86_64, x86. Версия движка одна для всех.
Запуск: python tools/fetch_rclone.py [версия] [вариант ...]  (по умолчанию 1.75.2 и все четыре варианта)."""
import hashlib, io, os, sys, urllib.request, zipfile

def get(u): return urllib.request.urlopen(urllib.request.Request(u, headers={'User-Agent': 'curl/8'}), timeout=300).read()

VARIANTS = {'arm64': ('linux-arm64', 'arm64-v8a'), 'arm32': ('linux-arm-v7', 'armeabi-v7a'), 'x86_64': ('linux-amd64', 'x86_64'), 'x86': ('linux-386', 'x86')}
ver = sys.argv[1] if len(sys.argv) > 1 else '1.75.2'
want_vars = [a for a in sys.argv[2:] if a in VARIANTS] or list(VARIANTS)
base = f'https://downloads.rclone.org/v{ver}/'
sums = get(base + 'SHA256SUMS').decode()

for var in want_vars:
    osarch, abi = VARIANTS[var]
    name = f'rclone-v{ver}-{osarch}.zip'
    want = next(l.split()[0] for l in sums.splitlines() if l.strip().endswith(name))
    data = get(base + name)
    got = hashlib.sha256(data).hexdigest()
    if got != want: sys.exit(f'SHA-256 не совпал: {got} != {want}')
    z = zipfile.ZipFile(io.BytesIO(data))
    member = next(n for n in z.namelist() if n.endswith('/rclone'))
    b = z.read(member)

    # страница после входа: «All done. Please go back to rclone.» -> «Готово. Вернись в ADB-phon» (длина области та же, лишнее добивается пробелами)
    old = ('{{ else }}\nAll done. Please go back to rclone.\n{{ end }}\n</pre>\n</body>\n</html>\n').encode()
    new_core = '{{ else }}Готово. Вернись в ADB-phon{{ end }}</pre></body></html>'.encode()
    if len(new_core) > len(old): sys.exit('новый текст длиннее старого')
    new = new_core + b' ' * (len(old) - len(new_core))
    if b.count(old) != 1: sys.exit(f'область страницы найдена {b.count(old)} раз (ожидался 1): версия {ver} требует правки скрипта')
    b = b.replace(old, new)
    assert len(new) == len(old)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'clouds', 'src', var, 'jniLibs', abi, 'libclouds.so')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'wb').write(b)
    print('OK', ver, 'официальный SHA-256', want, '| итоговый SHA-256', hashlib.sha256(b).hexdigest(), '|', len(b), 'байт ->', os.path.normpath(out))
