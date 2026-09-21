# 书源 JS 反射调用 JsVarName（AppConst 内部类, 随 AppConst 下沉, 等价原 @Keep）。
-keep class io.legado.app.constant.AppConst$JsVarName { *; }

# JsURL 原 @Keep（androidx 注解不入 commonMain），JS 桥反射访问属性。
-keep class io.legado.app.utils.JsURL { *; }
