# Kotlinx Serialization (Keep @Serializable DTOs and serializers across :shared:contracts and :app)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pizzora.contracts.**$$serializer { *; }
-keepclassmembers class com.pizzora.contracts.** {
    *** Companion;
}
-keepclasseswithmembers class com.pizzora.contracts.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.example.navigation.** { *; }
-keep,includedescriptorclasses class com.example.update.**$$serializer { *; }
-keepclassmembers class com.example.update.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.update.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Ktor Client & OkHttp
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Razorpay Android Checkout SDK
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-keepclasseswithmembers class * implements com.razorpay.PaymentResultWithDataListener {
    public void onPaymentSuccess(...);
    public void onPaymentError(...);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JNA & UniFFI Native Bindings
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public *;
}
-keepclassmembers class * implements com.sun.jna.Callback {
    <methods>;
}
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn com.sun.jna.**
-keep class uniffi.pizzora_uniffi.** { *; }

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
