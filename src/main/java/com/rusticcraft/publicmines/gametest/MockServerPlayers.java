package com.rusticcraft.publicmines.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * GameTest mock {@link ServerPlayer} support.
 *
 * <p>{@link GameTestHelper#makeMockServerPlayerInLevel()} places the player
 * through the real login path, so every mod listener that sends a custom
 * payload on join (KubeJS server-data sync, physics libs, and similar) reaches
 * {@code ServerCommonPacketListenerImpl#send} while the fake connection still
 * has no negotiated channels; {@code NetworkRegistry#checkPacket} then throws
 * and the test fails for reasons unrelated to its assertions.
 *
 * <p>{@link #inLevel(GameTestHelper)} builds the same mock but calls
 * {@link NetworkRegistry#configureMockConnection(Connection)} before
 * {@code placeNewPlayer}, so the connection presents itself as a fully
 * negotiated NeoForge client: join-time payloads from pack mods are accepted
 * onto the embedded channel and harmlessly discarded, exactly like a real
 * modded client that completed channel negotiation. The assertions each
 * GameTest makes are unchanged; only the environment binding is removed.
 */
public final class MockServerPlayers {
    private MockServerPlayers() { }

    /**
     * Creates a mock server player placed in the helper's level whose fake
     * connection advertises every registered payload channel, so modded
     * join/login packets sent during {@code placeNewPlayer} do not fault the
     * test. Mirrors the deprecated {@code makeMockServerPlayerInLevel} (same
     * creative-mode anonymous player) with the negotiation step pre-completed.
     */
    public static ServerPlayer inLevel(GameTestHelper helper) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
