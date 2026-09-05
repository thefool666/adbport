# ===== 项目专属 R8/ProGuard 规则 =====

# libadb 依赖产物不完整：AdbProtocol.generateAuth() 引用了内部类 AuthType，
# 但该类未包含在依赖中。R8 首次收缩时发现缺类会直接构建失败。
# 已知悉此缺失，放行警告。若运行时配对分支报 NoClassDefFoundError，
# 则需更换 libadb 依赖版本（那是唯一根治方案）。
-dontwarn io.github.muntashirakon.adb.AdbProtocol$AuthType

# libadb：核心依赖，整体保留，防止反射调用被误剪
-keep class io.github.muntashirakon.adb.** { *; }

# Conscrypt：加密提供器，反射密集，R8 静态分析追踪不到，必须整体保留
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# BouncyCastle：SPAKE2 配对算法依赖，同样防误剪
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# 保留崩溃时的行号信息，方便日后从 logcat 定位问题（代价可忽略）
-keepattributes SourceFile,LineNumberTable
