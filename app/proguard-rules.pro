# Keep line numbers for readable crash reports; R8 already shrinks Compose.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The VPN service is instantiated by the system.
-keep class dev.addblocker.adshield.vpn.AdBlockVpnService { *; }
-keep class dev.addblocker.adshield.service.AdBlockTileService { *; }
