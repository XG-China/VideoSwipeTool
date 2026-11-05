# 正式版保留规则
# 清单里声明的组件 AGP 会自动保留,这里再显式写一遍,避免后续改动被裁掉
-keep class com.autoskip.ad.AdSkipAccessibilityService { *; }
-keep class com.autoskip.ad.GuardService { *; }
-keep class com.autoskip.ad.ActionReceiver { *; }
-keep class com.autoskip.ad.MainActivity { *; }

# 保留行号,方便看崩溃栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
