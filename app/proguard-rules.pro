# Room, DataStore and Compose ship their own consumer rules.
# Keep enum names stored as strings in DataStore/Room.
-keepclassmembers enum com.batterylens.** { *; }
