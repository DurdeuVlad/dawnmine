package com.rusticcraft.auditprobe;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
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
                        .executes(context -> dumpBlockTags(context.getSource()))));
    }

    private int dumpBlockTags(net.minecraft.commands.CommandSourceStack source) {
        try {
            Path output = Path.of("reports", "runtime-block-tags.tsv");
            Files.createDirectories(output.getParent());
            List<String> rows = new ArrayList<>();
            rows.add("block_id\ttag_id");
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                Block block = entry.getValue();
                var id = BuiltInRegistries.BLOCK.getKey(block);
                var holder = BuiltInRegistries.BLOCK.getHolder(ResourceKey.create(Registries.BLOCK, id)).orElse(null);
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
            source.sendSuccess(() -> Component.literal("Wrote " + (rows.size() - 1) + " block/tag rows to " + output), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Could not write runtime block-tag audit", e);
            source.sendFailure(Component.literal("Block-tag audit failed: " + e.getMessage()));
            return 0;
        }
    }
}









