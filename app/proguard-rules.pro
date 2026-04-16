# Sony GPS Link — ProGuard Rules

# Keep BLE GATT callback class names (Android calls them via reflection)
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# Keep app classes
-keep class com.example.sonygps.** { *; }

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }

# Standard Android
-dontwarn android.bluetooth.**
