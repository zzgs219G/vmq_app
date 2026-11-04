# ============================================================
#  ProGuard / R8 规则
#
#  release 构建开启了 isMinifyEnabled + isShrinkResources。
#  这里只保留「混淆后会真的坏掉」的东西，其余交给 R8 默认规则。
# ============================================================

# ---------- Room ----------
# Room 通过反射读取实体的列名与 getter，字段名被重命名会导致
# 「Cannot find setter/getter for field」或读出的数据全为默认值。
-keep class com.xixin.vmq.ledger.Txn { *; }
-keepclassmembers class com.xixin.vmq.ledger.Txn {
    <fields>;
    <init>(...);
}

# Room 生成的 *Dao_Impl / *Database_Impl 由框架按名查找
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.room.RoomDatabase_Impl { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * implements androidx.room.Dao { *; }

# Room 注解需要保留（运行期仍会读取部分）
-keepattributes *Annotation*, AnnotationDefault, Signature, InnerClasses, EnclosingMethod

# ---------- Kotlin 协程 ----------
# 协程的 Continuation 类依赖类名与状态机结构，被裁剪会抛
# 「ClassNotFoundException」或状态机错乱
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---------- Ktor / OkHttp ----------
# Ktor 引擎通过 ServiceLoader 与反射加载，避免被裁掉
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# ---------- org.json ----------
-keep class org.json.** { *; }

# ---------- Android 组件（清单里声明的，R8 会自动保留，这里显式声明意图） ----------
-keep class com.xixin.vmq.service.PaymentNotificationListener { *; }
-keep class com.xixin.vmq.keepalive.WatchdogReceiver { *; }
-keep class com.xixin.vmq.keepalive.BootReceiver { *; }

# ---------- 排查用：保留行号，崩溃栈可读 ----------
# 需要彻底隐藏原始源码位置时注释掉这两行
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 可选：保留泛型信息（Gson/序列化会用到） ----------
-keepattributes Signature
