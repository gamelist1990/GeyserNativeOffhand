package org.pexserver.nativeoffhand.tests;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/** Install ONLY on an isolated integration-test server. */
public final class PlacementTestHooks extends JavaPlugin implements Listener {
    private final java.util.List<Long> placements = new java.util.ArrayList<>();
    private long rateStart;
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("rate-reset")) {
            placements.clear(); rateStart = System.nanoTime(); sender.sendMessage("rate-reset"); return true;
        }
        if (args.length > 0 && args[0].equals("rate-report")) {
            sender.sendMessage("{\"placementMillis\":" + placements + "}"); return true;
        }
        var player = getServer().getPlayerExact("NativeOH");
        sender.sendMessage(player == null ? "offline" : "sneaking=" + player.isSneaking() + " input=" + player.getCurrentInput().isSneak() + " yaw=" + player.getLocation().getYaw() + " block=" + player.getWorld().getBlockAt(2, 65, 0).getBlockData().getAsString());
        return true;
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        var tags = event.getPlayer().getScoreboardTags();
        if (tags.contains("nativeoh_cancel_interact")) event.setCancelled(true);
        if (tags.contains("nativeoh_cooldown") && event.getHand() == EquipmentSlot.HAND)
            event.getPlayer().setCooldown(Material.APPLE, 20);
    }
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void record(BlockPlaceEvent event) {
        if (event.getPlayer().getName().equals("NativeOH") && event.getHand() == EquipmentSlot.OFF_HAND)
            placements.add((System.nanoTime() - rateStart) / 1_000_000);
    }
    @EventHandler public void place(BlockPlaceEvent event) {
        if (event.getPlayer().getScoreboardTags().contains("nativeoh_cancel_place")) event.setCancelled(true);
    }
}
