# Appens egen kode beholdes som den er (ingen fjerning/omdøping) - trygt for Room,
# WorkManager, widgeten og enum.valueOf, og krasjrapporter blir lesbare uten mapping.
# Bibliotekene krympes fortsatt, og det er der størrelsen ligger.
-keep class no.healthlog.app.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
