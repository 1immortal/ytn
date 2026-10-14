# kotlinx.serialization (WearStatus in :wearlink)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.truenascompanion.wearlink.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.truenascompanion.wearlink.**$$serializer { *; }
