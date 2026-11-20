# AMap location SDK. Official rules (https://lbs.amap.com/api/android-location-sdk/guide/create-project/dev-attention)
# only list com.amap.api.location/fence, com.loc and com.autonavi.aps.amapapi.model, but 11.x also ships
# com.amap.location.* and com.autonavi.aps.* used from libapssdk.so, so keep all of the SDK's packages.
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
# Optional classes referenced by the SDK but not shipped with it
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
