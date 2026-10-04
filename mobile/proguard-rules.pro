# De regels voor de datalaag (Jsoup, OkHttp, de eigen modellen) komen mee uit
# :core via consumer-rules.pro.

# De Cast-bibliotheek vindt de opties via de naam in het manifest.
-keep class com.xiappdesign.family7.mobile.cast.CastOptionsProvider { *; }
