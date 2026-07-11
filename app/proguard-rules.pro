# LastLauncher keeps everything it needs via standard AndroidX consumer rules.
# Keep the accessibility service (referenced from the manifest only).
-keep class fr.arichard.lastlauncher.lock.LockService { *; }
