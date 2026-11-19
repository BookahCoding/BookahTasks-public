# kotlinx.serialization and OkHttp ship their own consumer rules.
# biweekly 0.6.8 has optional Jackson (jCal) and XML (xCal) code paths that this app never uses; rules are added below only when R8 names a class.

# WorkManager keeps its job list in a generated Room database; R8 removed its no-argument constructor
# ("NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []" on start). Rule added because R8 named the class.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# biweekly reads biweekly/biweekly.properties relative to its own Biweekly class. R8's full optimisation moved the class to the default
# package, the file was not found and every calendar object failed to parse (NullPointerException in Properties.load, seen only in the
# real release build, not in the minified debug build). Keeping the class where it is keeps the lookup working.
-keep class biweekly.Biweekly { *; }
