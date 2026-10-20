-dontobfuscate
# Methods called from page JavaScript.
-keepclassmembers class com.clearbrowser.tv.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
