package org.pexserver.nativeoffhand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.Plugin;

/** Same-server Paper adapter. All item/block rules run on the server thread. */
final class PaperUseBridge {
    private static final Field TELEPORT = field("awaitingPositionFromClient");
    private static final Field DESTROY = field(net.minecraft.server.level.ServerPlayerGameMode.class, "isDestroyingBlock");
    private static final Method LIMIT = method("checkLimit", long.class);
    private static Plugin host;
    private static final String CHANNEL = "nativeoffhand:use";
    private record Request(org.geysermc.geyser.session.GeyserSession session, int slot, int sequence, BlockHitResult hit, long timestamp, long clientTick) {}
    private static final java.util.Map<Long, Request> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.security.SecureRandom TOKENS = new java.security.SecureRandom();
    private static final org.bukkit.plugin.messaging.PluginMessageListener LISTENER = (channel, sender, data) -> receive(sender, data);
    private record UseClock(long clientTick, long acceptedNanos) {}
    private static final long USE_DELAY_NANOS = 200_000_000L;
    private static final java.util.Map<ServerPlayer, UseClock> LAST_USE = new java.util.WeakHashMap<>();

    static void initialize() {
        host = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (host == null) throw new IllegalStateException("Vanilla use routing requires same-server Paper/Geyser-Spigot");
        Bukkit.getMessenger().registerIncomingPluginChannel(host, CHANNEL, LISTENER);
    }

    private static Field field(String name) { return field(ServerGamePacketListenerImpl.class, name); }
    private static Field field(Class<?> type, String name) {
        try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Paper interaction fields", e); }
    }
    private static Method method(String name, Class<?>... parameters) {
        try { var m = ServerGamePacketListenerImpl.class.getDeclaredMethod(name, parameters); m.setAccessible(true); return m; }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Paper interaction methods", e); }
    }

    static void submit(org.geysermc.geyser.session.GeyserSession session, int slot, int sequence, BlockHitResult hit, long timestamp) {
        PENDING.values().removeIf(r -> r.session().isClosed() || timestamp - r.timestamp() > 5000);
        if (PENDING.size() >= 256) return;
        long token;
        var request = new Request(session, slot, sequence, hit, timestamp, session.getClientTicks());
        do { token = TOKENS.nextLong(); } while (PENDING.putIfAbsent(token, request) != null);
        // Travel through the SAME Java connection as rotation, movement,
        // sneak and carried-slot packets; a Bukkit task can overtake them.
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes.writeString(buffer, CHANNEL);
            buffer.writeLong(token);
            session.sendDownstreamGamePacket(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket(buffer));
        } finally { buffer.release(); }
    }

    private static void receive(org.bukkit.entity.Player sender, byte[] data) {
        if (data.length != Long.BYTES) return;
        var request = PENDING.remove(java.nio.ByteBuffer.wrap(data).getLong());
        if (request == null || request.session().isClosed()
                || !sender.getUniqueId().equals(request.session().javaUuid())
                || System.currentTimeMillis() - request.timestamp() > 5000) return;
        if (!(sender instanceof CraftPlayer craft)) return;
        // Paper dispatches plugin messages on its server thread in connection order.
        try { use(craft.getHandle(), request.slot(), request.sequence(), request.hit(), request.timestamp(), request.clientTick()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot validate Paper interaction", e); }
    }

    static void forget(org.geysermc.geyser.session.GeyserSession session) {
        PENDING.values().removeIf(r -> r.session() == session);
    }
    static void clear() {
        if (host != null) Bukkit.getMessenger().unregisterIncomingPluginChannel(host, CHANNEL, LISTENER);
        PENDING.clear(); LAST_USE.clear(); host = null;
    }

    private static void use(ServerPlayer p, int slot, int sequence, BlockHitResult hit, long timestamp, long clientTick)
            throws ReflectiveOperationException {
        var connection = p.connection;
        if (slot < 0 || slot > 8 || p.isImmobile() || p.isUsingItem() || !connection.hasClientLoaded()
                || DESTROY.getBoolean(p.gameMode) || !(boolean) LIMIT.invoke(connection, timestamp)) return;
        connection.ackBlockChangesUpTo(sequence);
        long now = System.nanoTime();
        var last = LAST_USE.get(p);
        // Minecraft.rightClickDelay counts CLIENT ticks, not Bukkit/server
        // ticks. Server catch-up or tick sprint must never release a burst.
        if (last != null && (clientTick - last.clientTick() < 4 || now - last.acceptedNanos() < USE_DELAY_NANOS)) {
            if (hit != null) correct(p, hit);
            return;
        }
        LAST_USE.put(p, new UseClock(clientTick, now));
        // The preceding ordinary carried-slot packet fires Paper's held-item
        // event. Do not override a plugin that rejected that slot selection.
        if (p.getInventory().getSelectedSlot() != slot) { if (hit != null) correct(p, hit); return; }
        var level = p.level();
        if (hit != null) {
            var pos = hit.getBlockPos();
            var offset = hit.getLocation().subtract(Vec3.atCenterOf(pos));
            if (!hit.getLocation().isFinite() || !p.isWithinBlockInteractionRange(pos, 1.0)
                    || Math.abs(offset.x()) >= 1.0000001 || Math.abs(offset.y()) >= 1.0000001
                    || Math.abs(offset.z()) >= 1.0000001) return;
            // MultiPlayerGameMode rejects a target beyond the world border.
            if (!level.getWorldBorder().isWithinBounds(pos)) { correct(p, hit); return; }
            p.resetLastActionTime();
            if (pos.getY() > level.getMaxY() || pos.getY() < level.getMinY()) {
                p.sendBuildLimitMessage(pos.getY() > level.getMaxY(), pos.getY() > level.getMaxY() ? level.getMaxY() : level.getMinY());
                correct(p, hit); return;
            }
            boolean sign = level.paperConfig().spawn.allowUsingSignsInsideSpawnProtection
                && level.getBlockState(pos).getBlock() instanceof SignBlock;
            if (!sign && MinecraftServer.getServer().isUnderSpawnProtection(level, pos, p)) {
                p.sendSpawnProtectionMessage(pos); correct(p, hit); return;
            }
            if (TELEPORT.get(connection) != null || (!sign && !level.mayInteract(p, pos))) {
                correct(p, hit); return;
            }
        } else if (TELEPORT.get(connection) != null) return;
        try {
            // Minecraft.startUseItem: block SUCCESS/FAIL stops; otherwise try
            // air use for that SAME hand before advancing to the other hand.
            boolean firstAction = true;
            for (var hand : InteractionHand.values()) {
                var stack = p.getItemInHand(hand);
                if (!stack.isItemEnabled(level.enabledFeatures())) return;
                if (hit != null) {
                    if (!firstAction && !(boolean) LIMIT.invoke(connection, timestamp)) return;
                    firstAction = false;
                    var animation = stack.getInteractAnimation();
                    var result = p.gameMode.useItemOn(p, level, stack, hand, hit);
                    if (result.consumesAction()) CriteriaTriggers.ANY_BLOCK_USE.trigger(p, hit.getBlockPos(), stack);
                    swing(p, hand, animation, result);
                    if (result instanceof InteractionResult.Success || result instanceof InteractionResult.Fail) return;
                    // Paper plugin denial must not turn into an offhand bypass.
                    if (p.gameMode.interactResult && !p.getCooldowns().isOnCooldown(stack)) { p.resyncUsingItem(p); return; }
                    if (placementAttempt(p, stack) && ((hit.getDirection() == Direction.UP && hit.getBlockPos().getY() >= level.getMaxY())
                            || (hit.getDirection() == Direction.DOWN && hit.getBlockPos().getY() <= level.getMinY()))) {
                        boolean upper = hit.getDirection() == Direction.UP;
                        p.sendBuildLimitMessage(upper, upper ? level.getMaxY() : level.getMinY());
                    }
                    // A vanilla spectator's client returns CONSUME even when
                    // there is no menu; it never advances to the offhand.
                    if (p.isSpectator()) return;
                }
                if (!p.getItemInHand(hand).isEmpty()) {
                    if (!firstAction && !(boolean) LIMIT.invoke(connection, timestamp)) return;
                    firstAction = false;
                    if (air(p, hand) instanceof InteractionResult.Success) return;
                }
            }
        } finally {
            if (hit != null) correct(p, hit);
            p.inventoryMenu.broadcastChanges();
            if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions)
                p.detectEquipmentUpdates();
        }
    }

    private static boolean placementAttempt(ServerPlayer p, ItemStack stack) {
        return !stack.isEmpty() && !p.getCooldowns().isOnCooldown(stack)
            && (stack.getItem() instanceof BlockItem || stack.getItem() instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY);
    }

    private static InteractionResult air(ServerPlayer p, InteractionHand hand) {
        var stack = p.getItemInHand(hand);
        var level = p.level();
        if (p.isSpectator() || p.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
        var from = p.getEyePosition();
        var ray = level.clip(new ClipContext(from, from.add(p.getViewVector(1).scale(p.blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        boolean denied;
        if (ray.getType() == HitResult.Type.BLOCK) {
            if (p.gameMode.firedInteract && ray.getBlockPos().equals(p.gameMode.interactPosition)
                    && hand == p.gameMode.interactHand && ItemStack.isSameItemSameComponents(p.gameMode.interactItemStack, stack))
                denied = p.gameMode.interactResult;
            else denied = CraftEventFactory.callPlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, ray.getBlockPos(),
                ray.getDirection(), stack, true, hand, ray.getLocation()).useItemInHand() == Event.Result.DENY;
            p.gameMode.firedInteract = false;
        } else denied = CraftEventFactory.callPlayerInteractEvent(p, Action.RIGHT_CLICK_AIR, stack, hand).useItemInHand() == Event.Result.DENY;
        if (denied) { p.resyncUsingItem(p); return InteractionResult.SUCCESS; }
        stack = p.getItemInHand(hand);
        if (stack.isEmpty() || !stack.isItemEnabled(level.enabledFeatures())) return InteractionResult.PASS;
        p.resetLastActionTime();
        var animation = stack.getInteractAnimation();
        var result = p.gameMode.useItem(p, level, stack, hand);
        swing(p, hand, animation, result);
        return result;
    }

    private static void swing(ServerPlayer p, InteractionHand hand, net.minecraft.world.item.component.SwingAnimation animation, InteractionResult result) {
        if (result instanceof InteractionResult.Success success && success.shouldSwing())
            p.swingAndResetAttackStrength(hand, animation, success.swingSource() != InteractionResult.SwingSource.PREDICTED);
    }
    private static void correct(ServerPlayer p, BlockHitResult hit) {
        p.connection.send(new ClientboundBlockUpdatePacket(p.level(), hit.getBlockPos()));
        p.connection.send(new ClientboundBlockUpdatePacket(p.level(), hit.getBlockPos().relative(hit.getDirection())));
        p.containerMenu.forceHeldSlot(InteractionHand.MAIN_HAND);
        p.containerMenu.forceHeldSlot(InteractionHand.OFF_HAND);
    }
}
