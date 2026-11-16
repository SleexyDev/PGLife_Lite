-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }

-keep class com.example.devicecontrol.data.** { *; }
-keepclassmembers class com.example.devicecontrol.data.** { *; }

# Compile-only ErrorProne annotations referenced by Tink (security-crypto)
-dontwarn com.google.errorprone.annotations.**
