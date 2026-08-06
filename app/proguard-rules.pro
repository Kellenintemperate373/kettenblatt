# osmdroid reads configuration through reflection and names its tile sources by
# class, so keep the library intact rather than chase individual keep rules.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# kotlinx.serialization generates serializers that are looked up by name.
-keepclassmembers class de.kettenblatt.** {
    *** Companion;
}
-keepclasseswithmembers class de.kettenblatt.** {
    kotlinx.serialization.KSerializer serializer(...);
}
