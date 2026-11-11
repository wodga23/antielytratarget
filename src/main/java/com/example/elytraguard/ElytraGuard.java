package com.example.elytraguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerElytraBoostEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ElytraGuard extends JavaPlugin implements Listener {

    private final Map<UUID, Long> lastBoost = new HashMap<>();
    private final Map<UUID, Entity> boostRocket = new HashMap<>();
    private final Map<UUID, Boolean> swappedBeforeBoost = new HashMap<>();
    private final Map<UUID, Long> lastSlotChange = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastViolation = new HashMap<>();

    private int kickAt;
    private long decayMs;
    private int logEvery;
    private String kickMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kickAt = getConfig().getInt("kick-at", 5);
        decayMs = getConfig().getLong("decay-seconds", 60) * 1000L;
        logEvery = Math.max(1, getConfig().getInt("log-every", 5));
        kickMessage = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-message", "&cHile tespit edildi!"));
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // Fişek kullanımı
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoost(PlayerElytraBoostEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        lastBoost.put(id, now);
        boostRocket.put(id, e.getFirework());

        // Slot kısa süre önce değiştiyse "silent" şüphesi
        long lastSlot = lastSlotChange.getOrDefault(id, 0L);
        swappedBeforeBoost.put(id, now - lastSlot < 100);

        // Elde fişek yoksa direkt silent
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        boolean holding = main.getType().name().equals("FIREWORK_ROCKET")
                || off.getType().name().equals("FIREWORK_ROCKET");
        if (!holding) {
            e.setCancelled(true);
            punishSilent(p);
        }
    }

    // Fişek bastıktan hemen sonra slota geri dönme (silent swap)
    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        long boost = lastBoost.getOrDefault(id, 0L);
        if (now - boost < 150 && swappedBeforeBoost.getOrDefault(id, false)) {
            swappedBeforeBoost.put(id, false);
            punishSilent(p);
        }
        lastSlotChange.put(id, now);
    }

    // Fişekle uçarken kılıç/balta vb. ile vurmayı engelle
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        if (!p.isGliding() || !isBoosting(p)) return;

        String type = p.getInventory().getItemInMainHand().getType().name();
        boolean weapon = type.endsWith("_SWORD") || type.endsWith("_AXE")
                || type.equals("MACE") || type.equals("TRIDENT");
        if (!weapon) return;

        e.setCancelled(true);
        addViolation(p, "Fişekle uçarken " + type + " ile vurmaya çalıştı");
    }

    private boolean isBoosting(Player p) {
        UUID id = p.getUniqueId();
        Entity rocket = boostRocket.get(id);
        if (rocket != null && rocket.isValid()) return true;
        return System.currentTimeMillis() - lastBoost.getOrDefault(id, 0L) < 500;
    }

    private void punishSilent(Player p) {
        // Yıldırım ana thread'de çarpsın
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            p.getWorld().strikeLightning(p.getLocation());
        });
        addViolation(p, "Silent fişek tespit edildi");
    }

    private void addViolation(Player p, String reason) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        // Süre geçtiyse eski ihlalleri sıfırla
        if (now - lastViolation.getOrDefault(id, 0L) > decayMs) {
            violations.put(id, 0);
        }
        lastViolation.put(id, now);

        int vl = violations.merge(id, 1, Integer::sum);

        // Log spam'ini önle: ilk ihlal ve her N ihlalde bir
        if (vl == 1 || vl % logEvery == 0) {
            getLogger().warning(p.getName() + " | " + reason + " | VL: " + vl);
        }

        if (vl >= kickAt) {
            violations.put(id, 0);
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) p.kickPlayer(kickMessage);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastBoost.remove(id);
        boostRocket.remove(id);
        swappedBeforeBoost.remove(id);
        lastSlotChange.remove(id);
        violations.remove(id);
        lastViolation.remove(id);
    }
}
