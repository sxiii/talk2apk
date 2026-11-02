# Talk2APK 0.5.3 resource-linking fix

This release removes all app-owned Android resource references for the logo from the manifest and Kotlin UI. The launcher uses Android's built-in default app icon, and the in-app toolbar logo is drawn with Compose primitives. This avoids AAPT/R resource generation failures when an icon resource is missing from a repository checkout.

## Important update instructions

Extract this archive and copy the **contents of the inner `talk2apk-v0.5.3` directory** into the root of the existing GitHub repository, preserving paths. Commit and push. Then open the new Actions run and verify its commit SHA matches the new commit. If the error still reports `R.drawable.talk2apk_icon` or `@drawable/talk2apk_icon`, GitHub is building an older commit or the files were not overwritten.

This archive has been statically checked for XML well-formedness and remaining app-owned `R.drawable`, `R.mipmap`, `@drawable`, or `@mipmap` references. A full Gradle build was not run in the generation environment.
