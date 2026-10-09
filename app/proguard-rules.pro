# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Classes stored as JSON in SharedPreferences via Gson reflection.
-keepattributes Signature
-keep class com.ksjd.testem.AccountSnapshot { *; }
-keep class com.ksjd.testem.AccountDetails { *; }
-keep class com.ksjd.testem.SavedRoute { *; }
-keep class com.ksjd.testem.CpStopSuggestion { *; }
# Saved journeys and regular rides (2.6). Without these, R8 renames or drops the fields Gson
# fills, so they read back empty after a restart and are lost after every update.
-keep class com.ksjd.testem.SavedJourney { *; }
-keep class com.ksjd.testem.RecurringRide { *; }
-keep class com.ksjd.testem.TimetableConnection { *; }
-keep class com.ksjd.testem.TimetableSegment { *; }
# Journey legs travel to the tracking service as JSON.
-keep class com.ksjd.testem.live.TrackedTrip { *; }
-keep class com.ksjd.testem.live.TripRef { *; }
-keep class * extends com.google.gson.reflect.TypeToken
# IDS BBSK account (2.13, EM-0043): stored with Gson, so its classes and generic types must keep their shape,
# or the saved lists come back as Gson maps and the account screen crashes reading them.
-keep class com.ksjd.testem.IdsAccount { *; }
-keep class com.ksjd.testem.IdsIdentifier { *; }
-keep class com.ksjd.testem.IdsTicket { *; }
-keep class com.ksjd.testem.IdsTopUp { *; }
-keep class com.ksjd.testem.CredentialsManager$ReportDraft { *; }
