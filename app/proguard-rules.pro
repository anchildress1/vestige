# ObjectBox model — keep generated MyObjectBox + entity classes from being stripped.
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keepclassmembers class * extends io.objectbox.converter.PropertyConverter { *; }

# LiteRT / LiteRT-LM native bindings — verify exact rules at Phase 0.
# litertlm-android bundles the underlying litert runtime so we keep both packages.
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
