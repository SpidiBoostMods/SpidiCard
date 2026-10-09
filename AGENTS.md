# SpidiCard project rules

- Author and distribution metadata: SpidiBoost. Mod Menu name: `spidiboost.SpidiCard`.
- Minecraft 1.21.4, Fabric, Java 21. Keep the mod independent except for Fabric API and libraries already provided by Minecraft.
- Publish every new release to `SpidiBoostMods/SpidiCard` using the publisher account `SpidiBoostDev`. Preserve other GitHub accounts and their settings.
- Increment `mod_version` before a new release. Run the relevant local tests and native client checks for changes to Minecraft behavior or UI. Compilation alone is not runtime verification.
- Commit the source and push an annotated `vX.Y.Z` tag. The release workflow must pass Windows, macOS and Linux tests before publishing assets.
- Verify the public latest-release manifest, downloaded JAR identity and SHA-256 after publication. Keep the updater endpoint and supported Minecraft version consistent with the release.
- Never upload live instance files, configs, logs, player results, credentials or launcher arguments. Never embed a publisher token in the mod. Source archives contain project files only.
- Updates must preserve results, configuration, worlds and unrelated mods; replace only the loaded SpidiCard JAR in its own instance after the owning JVM exits. Preserve a backup.
- Prefer native Prism CLI restart. Support standard Fabric launches using tokenized arguments; use installation after ordinary exit when launcher wrappers or Windows encodings make replay unreliable.
- Never stop or modify the user's running Minecraft or recording to perform verification. Use the isolated verification instance.
