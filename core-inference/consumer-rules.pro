# LiteRT / LiteRT-LM native bindings.
# litertlm-android bundles the underlying litert runtime; both packages must survive R8.
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
