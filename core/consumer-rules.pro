# Jsoup bouwt zijn tekenreeksen (entities-*.properties) uit resources en gebruikt
# reflectie in de parser; laat de hele bibliotheek en die resources met rust.
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# OkHttp en Okio leveren eigen regels mee, maar deze waarschuwingen komen van
# optionele platformklassen die op Android niet bestaan.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# De schermen praten met Media3 via de officiele API; de sessie- en UI-modules
# leveren eigen regels mee. Alleen de weergavenamen van onze eigen modellen
# blijven nodig voor leesbare foutmeldingen.
-keepnames class com.xiappdesign.family7.core.data.** { *; }

# org.json zit in het platform.
-dontwarn org.json.**
