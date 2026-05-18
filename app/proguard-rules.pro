# App-only ProGuard rules go here.
#
# ObjectBox and LiteRT-LM keep rules ship as consumer-rules.pro on their owning modules
# (:core-storage and :core-inference) and are merged into the app's R8 input automatically.
# Do not duplicate them here.
#
# localagents-rag 0.3.0 leaks compile-time-only AutoValue / protobuf annotations into the AAR
# bytecode. R8 only needs to ignore those RuntimeInvisible annotations; no runtime jar belongs
# in the APK just to satisfy annotation metadata.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoPresenceBits
