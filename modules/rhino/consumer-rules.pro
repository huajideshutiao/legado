# Add project specific ProGuard rules here.

## Rhino
-keep class
!org.mozilla.javascript.ast.**,
!org.mozilla.javascript.xml.**,
!org.mozilla.javascript.commonjs.**,
!org.mozilla.javascript.optimizer.**,
!org.mozilla.javascript.serialize.**,
org.mozilla.javascript.** { *; }

-dontwarn org.mozilla.javascript.engine.RhinoScriptEngineFactory
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
