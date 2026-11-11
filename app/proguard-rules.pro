# ADB-phon: правила для R8 (сжатие и обфускация релиза). Всё, что находится по имени строкой, по ресурсам или через JNI, оставляется.

# SSH (JSch): алгоритмы и классы подбираются по именам из настроек через Class.forName
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# SMB (jcifs-ng): настройки, поставщики и протоколы подбираются по именам классов
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# ADB по Wi-Fi (libadb-android), SPAKE2 и шифрование: поставщики и TLS работают по именам
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Архивы и сжатие
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**

# Сеть
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**

# Свои классы, к которым обращаются по имени: сервисы, ресиверы, окна из манифеста (их оставляет сам R8), стенд проверки и перечисления с сохранёнными именами
-keep class app.adbphon.NetTestActivity { *; }
-keepclassmembers enum app.adbphon.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# RAR5: родная часть UnRAR вызывается из JNI по именам
-keep class app.adbphon.RarNative { *; }

# Видеозвонок (7.0): мост JS ↔ Kotlin в окне звонка вызывается страницей по имени
-keepclassmembers class app.adbphon.CallActivity$Bridge { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
