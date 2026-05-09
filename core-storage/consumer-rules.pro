# ObjectBox model — keep generated MyObjectBox + entity classes from being stripped by R8 in :app.
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keepclassmembers class * extends io.objectbox.converter.PropertyConverter { *; }
