# Python (Chaquopy) вызывает Kotlin-объект по имени метода через рефлексию — см. debug_runner.py (_input_callback.call).
# Без этого правила R8 переименует/удалит метод, и input() в release-сборке перестанет работать.
-keep class com.vsmobile.editor.InputBridge { public *; }

-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ViewModel создаётся фабрикой через рефлексию по конструктору (Application, SavedStateHandle).
-keep class com.vsmobile.editor.EditorViewModel { <init>(...); }
