package io.github.octomiku01.modsync.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Opens the settings from Mod Menu */
public final class ModSyncModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new ModSyncScreen(parent, ModSyncClient.sync());
    }
}
