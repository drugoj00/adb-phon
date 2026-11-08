// JNI-обёртка над официальной библиотекой UnRAR (rarlab.com, лицензия UnRAR: только распаковка). Читает RAR 4 и RAR 5.
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "unrar/dll.hpp"

static std::u16string w2u16(const wchar_t *w) {
    std::u16string r;
    for (; *w; ++w) {
        unsigned c = (unsigned)*w;
        if (c >= 0x10000) { c -= 0x10000; r.push_back((char16_t)(0xD800 + (c >> 10))); r.push_back((char16_t)(0xDC00 + (c & 0x3FF))); }
        else r.push_back((char16_t)c);
    }
    return r;
}

// Пароль для зашифрованных заголовков запрашивается при открытии: отдаём его через обратный вызов.
static int CALLBACK pwCallback(UINT msg, LPARAM user, LPARAM p1, LPARAM p2) {
    const char *pw = (const char *)user;
    if (!pw || !*pw) return -1;
    if (msg == UCM_NEEDPASSWORD) { strncpy((char *)p1, pw, (size_t)p2 - 1); ((char *)p1)[p2 - 1] = 0; return 1; }
    if (msg == UCM_NEEDPASSWORDW) {
        wchar_t *w = (wchar_t *)p1; size_t n = 0;
        for (const unsigned char *c = (const unsigned char *)pw; *c && n + 1 < (size_t)p2; ) {   // UTF-8 -> wchar_t
            unsigned cp = *c++;
            if (cp >= 0xF0) { cp = ((cp & 7) << 18) | ((*c++ & 63) << 12); cp |= (*c++ & 63) << 6; cp |= (*c++ & 63); }
            else if (cp >= 0xE0) { cp = ((cp & 15) << 12) | ((*c++ & 63) << 6); cp |= (*c++ & 63); }
            else if (cp >= 0xC0) { cp = ((cp & 31) << 6) | (*c++ & 63); }
            w[n++] = (wchar_t)cp;
        }
        w[n] = 0; return 1;
    }
    return 0;
}

static void *openArc(const char *path, const char *pw, unsigned mode, unsigned *res) {
    RAROpenArchiveDataEx d; memset(&d, 0, sizeof d);
    d.ArcName = (char *)path; d.OpenMode = mode; d.Callback = pwCallback; d.UserData = (LPARAM)pw;
    void *h = RAROpenArchiveEx(&d);
    *res = d.OpenResult;
    if (h && pw && *pw) RARSetPassword(h, (char *)pw);
    return h;
}

extern "C" {

// Список: массив строк «имя\u0001размер\u0001упакованный\u0001время DOS\u0001crc\u0001флаги». При ошибке открытия: массив из одного элемента «!код».
JNIEXPORT jobjectArray JNICALL Java_app_adbphon_RarNative_list(JNIEnv *env, jclass, jstring jpath, jstring jpw) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *pw = jpw ? env->GetStringUTFChars(jpw, nullptr) : nullptr;
    unsigned res = 0;
    void *h = openArc(path, pw, RAR_OM_LIST, &res);
    std::vector<std::u16string> out;
    if (!h) { out.push_back(u"!" + std::u16string(1, (char16_t)(u'0' + res / 10)) + (char16_t)(u'0' + res % 10)); }
    else {
        static RARHeaderDataEx hd;
        int code;
        while ((code = RARReadHeaderEx(h, &hd)) == 0) {
            std::u16string s = w2u16(hd.FileNameW);
            char buf[160];
            unsigned long long un = ((unsigned long long)hd.UnpSizeHigh << 32) | hd.UnpSize;
            unsigned long long pk = ((unsigned long long)hd.PackSizeHigh << 32) | hd.PackSize;
            snprintf(buf, sizeof buf, "\x01%llu\x01%llu\x01%u\x01%u\x01%u", un, pk, hd.FileTime, hd.FileCRC, hd.Flags);
            for (const char *p = buf; *p; ++p) s.push_back((char16_t)(unsigned char)*p);
            out.push_back(s);
            if (RARProcessFile(h, RAR_SKIP, nullptr, nullptr) != 0) break;
        }
        if (code != 0 && code != 10 /*ERAR_END_ARCHIVE*/) {
            out.insert(out.begin(), std::u16string(u"!") + (char16_t)(u'0' + code / 10) + (char16_t)(u'0' + code % 10));
        }
        RARCloseArchive(h);
    }
    env->ReleaseStringUTFChars(jpath, path);
    if (pw) env->ReleaseStringUTFChars(jpw, pw);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)out.size(), sc, nullptr);
    for (size_t i = 0; i < out.size(); ++i) {
        jstring js = env->NewString((const jchar *)out[i].data(), (jsize)out[i].size());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

// Распаковать запись с номером index (в порядке списка) в файл outFile. 0 — успех, иначе код ошибки UnRAR (22 и 24 — нужен или неверен пароль).
JNIEXPORT jint JNICALL Java_app_adbphon_RarNative_extract(JNIEnv *env, jclass, jstring jpath, jint index, jstring jout, jstring jpw) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *outp = env->GetStringUTFChars(jout, nullptr);
    const char *pw = jpw ? env->GetStringUTFChars(jpw, nullptr) : nullptr;
    unsigned res = 0;
    void *h = openArc(path, pw, RAR_OM_EXTRACT, &res);
    int rc = h ? 0 : (int)res;
    if (h) {
        static RARHeaderDataEx hd;
        int i = 0, code;
        rc = 10;
        while ((code = RARReadHeaderEx(h, &hd)) == 0) {
            if (i == index) { rc = RARProcessFile(h, RAR_EXTRACT, nullptr, (char *)outp); break; }
            if ((rc = RARProcessFile(h, RAR_SKIP, nullptr, nullptr)) != 0) break;
            rc = 10; ++i;
        }
        if (code != 0 && code != 10) rc = code;
        RARCloseArchive(h);
    }
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jout, outp);
    if (pw) env->ReleaseStringUTFChars(jpw, pw);
    return rc;
}

}
