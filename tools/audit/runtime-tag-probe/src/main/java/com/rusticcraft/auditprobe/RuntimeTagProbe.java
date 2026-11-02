package com.rusticcraft.auditprobe;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mod(RuntimeTagProbe.MOD_ID)
public class RuntimeTagProbe {
    public static final String MOD_ID = "publicmines_auditprobe";
    private static final Logger LOGGER = LogUtils.getLogger();

    public RuntimeTagProbe(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Rustic Craft Public Mines loaded (isolated runtime audit helper)");
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("publicmines-audit")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("block-tags")
                        .executes(context -> dumpTags(context.getSource(), BuiltInRegistries.BLOCK, Registries.BLOCK, "block_id", "runtime-block-tags.tsv")))
                .then(Commands.literal("item-tags")
                        .executes(context -> dumpTags(context.getSource(), BuiltInRegistries.ITEM, Registries.ITEM, "item_id", "runtime-item-tags.tsv"))));
    }

    private <T> int dumpTags(net.minecraft.commands.CommandSourceStack source, net.minecraft.core.Registry<T> registry,
                             net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey, String idColumn, String fileName) {
        try {
            Path output = Path.of("reports", fileName);
            Files.createDirectories(output.getParent());
            List<String> rows = new ArrayList<>();
            rows.add(idColumn + "\ttag_id");
            for (var entry : registry.entrySet()) {
                var id = registry.getKey(entry.getValue());
                var holder = registry.getHolder(ResourceKey.create(registryKey, id)).orElse(null);
                if (holder == null) {
                    rows.add(id + "\t");
                    continue;
                }
                List<String> tags = holder.tags().map(tag -> tag.location().toString()).sorted().toList();
                if (tags.isEmpty()) rows.add(id + "\t");
                else for (String tag : tags) rows.add(id + "\t" + tag);
            }
            rows.subList(1, rows.size()).sort(Comparator.naturalOrder());
            Files.write(output, rows);
            String name = fileName;
            source.sendSuccess(() -> Component.literal("Wrote " + (rows.size() - 1) + " tag rows to " + output), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Could not write runtime tag audit", e);
            source.sendFailure(Component.literal("Tag audit failed: " + e.getMessage()));
            return 0;
        }
    }
}









