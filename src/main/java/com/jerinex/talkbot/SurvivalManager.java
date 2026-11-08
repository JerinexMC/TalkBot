package com.jerinex.talkbot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SurvivalManager {

    private final TalkBotPlugin plugin;
    private final Map<UUID, SurvivalTask> activeSurvivalBots = new HashMap<>();

    public SurvivalManager(TalkBotPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean toggleSurvival(Player botPlayer) {
        UUID uuid = botPlayer.getUniqueId();
        if (activeSurvivalBots.containsKey(uuid)) {
            stopSurvival(uuid);
            botPlayer.sendMessage("§cSurvival Bot mode disabled.");
            return false;
        } else {
            SurvivalTask task = new SurvivalTask(plugin, botPlayer);
            // Run survival tick loop every 20 ticks (1 second) for smooth and responsive progression
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, 0L, 20L);
            task.setBukkitTask(bukkitTask);
            activeSurvivalBots.put(uuid, task);
            botPlayer.sendMessage("§aSurvival Bot mode enabled! Automating main adventure progression...");
            return true;
        }
    }

    public void stopSurvival(UUID uuid) {
        SurvivalTask task = activeSurvivalBots.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isSurvivalActive(UUID uuid) {
        return activeSurvivalBots.containsKey(uuid);
    }

    public void shutdown() {
        for (SurvivalTask task : activeSurvivalBots.values()) {
            task.cancel();
        }
        activeSurvivalBots.clear();
    }
}
